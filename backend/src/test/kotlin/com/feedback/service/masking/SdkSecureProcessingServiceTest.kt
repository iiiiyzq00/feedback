package com.feedback.service.masking

import com.feedback.model.po.sdk.*
import com.feedback.model.sdk.*
import com.feedback.repository.MaskingAuditRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.repository.sdk.*
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import com.feedback.security.sdk.TransportKeyService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class SdkSecureProcessingServiceTest {
    @Test
    fun `decrypts masks persists and retains encrypted body until governed expiry`() {
        val dataKey = ByteArray(32) { (it + 1).toByte() }
        val crypto = EnvelopeCrypto()
        val encrypted = crypto.encrypt(
            """{"description":"请联系13812345678","app_internal_logs":"safe diagnostic log","account_context":{"account":"masked"},"business_context":{"device_sn":"SN-001","user_id":"user-001"}}""".toByteArray(),
            dataKey
        )
        val ingest = envelope(
            id = 1,
            encryptedDataKey = byteArrayOf(1),
            payloadNonce = encrypted.nonce,
            payloadCiphertext = encrypted.ciphertext,
            payloadTag = encrypted.tag,
            clientFeedbackId = "client-1",
            keyId = "key-v1"
        )
        val fixture = fixture(ingest, crypto)
        every { fixture.transport.unwrapDataKey(any(), "key-v1") } returns dataKey.copyOf()
        every { fixture.sources.findByRecordId("client-1") } returns null
        every { fixture.sources.insert(any()) } answers { firstArg<SdkFeedbackSourcePo>().also { it.id = 10 } }

        fixture.service.processOne(1)

        assertEquals("COMPLETED", ingest.ingestStatus)
        assertNotNull(ingest.payloadCiphertext)
        verify {
            fixture.contents.upsert(match {
                it.sourceId == 10L && it.description == "请联系[PHONE]" && it.maskingStatus == "PASS"
            })
        }
        verify { fixture.identities.upsertBatch(match { rows -> rows.any { it.identityType == "PHONE" } }) }
        verify {
            fixture.identities.upsertBatch(match { rows ->
                rows.any {
                    it.identityType == "DEVICE_SN" && it.contactCiphertext != null && it.expiresAt?.year == 2099
                }
            })
        }
        verify {
            fixture.identities.upsertBatch(match { rows ->
                rows.any {
                    it.identityType == "USER_ID" && it.contactCiphertext != null && it.expiresAt?.year == 2099
                }
            })
        }
        verify(exactly = 1) { fixture.identities.findBySourceIds(listOf(10L)) }
        verify {
            fixture.channels.upsert(match {
                it.diagnosticCiphertext != null && it.diagnosticTypes?.contains("app_internal_logs") == true &&
                    it.appInternalLogs == null && it.accountContext == null
            })
        }
        verify {
            fixture.dirtyMarker.mark(match {
                it.sourceIds == listOf(10L) && it.reason == FeedbackListProjectionDirtyReason.CHANNEL
            })
        }
    }

    @Test
    fun `residual long number is masked and automatically writes formal feedback`() {
        val dataKey = ByteArray(32) { (it + 1).toByte() }
        val crypto = EnvelopeCrypto()
        val encrypted = crypto.encrypt(
            """{"description":"未知编号 1234567890 需要复核","business_context":{}}""".toByteArray(),
            dataKey
        )
        val ingest = envelope(
            id = 2,
            encryptedDataKey = byteArrayOf(1),
            payloadNonce = encrypted.nonce,
            payloadCiphertext = encrypted.ciphertext,
            payloadTag = encrypted.tag,
            clientFeedbackId = "client-review",
            keyId = "key-v1"
        )
        val fixture = fixture(ingest, crypto)
        every { fixture.transport.unwrapDataKey(any(), "key-v1") } returns dataKey.copyOf()
        every { fixture.sources.findByRecordId("client-review") } returns null
        every { fixture.sources.insert(any()) } answers { firstArg<SdkFeedbackSourcePo>().also { it.id = 20 } }

        fixture.service.processOne(2)

        assertEquals("COMPLETED", ingest.ingestStatus)
        assertNull(ingest.lastErrorCode)
        verify { fixture.contents.upsert(match { it.description?.contains("[LONG_NUMBER_REMOVED]") == true }) }
        verify { fixture.channels.upsert(any()) }
    }

    @Test
    fun `terminal body failure remains durable when alert publication fails`() {
        val ingest = envelope(
            id = 3,
            encryptedDataKey = byteArrayOf(1),
            payloadNonce = ByteArray(12),
            payloadCiphertext = byteArrayOf(1, 2, 3),
            payloadTag = ByteArray(16),
            clientFeedbackId = "client-corrupt",
            keyId = "key-v1"
        )
        val alerts = mockk<IngestFailureAlertPublisher>()
        val fixture = fixture(ingest, EnvelopeCrypto(), alerts)
        every { fixture.transport.unwrapDataKey(any(), "key-v1") } returns ByteArray(32) { 7 }
        every { alerts.publish(any(), any()) } throws IllegalStateException("alert unavailable")

        assertDoesNotThrow { fixture.service.processOne(3) }

        assertEquals("FAILED", ingest.ingestStatus)
        assertEquals("FAILED", ingest.bodyProcessingStatus)
        assertNull(ingest.nextRetryAt)
        verify(exactly = 1) {
            alerts.publish(match { it.id == 3L && it.retryCount == 1 }, any())
        }
    }

    @Test
    fun `rsa unwrap failure is observable immediately while ingest remains retryable`() {
        val ingest = envelope(
            id = 4,
            encryptedDataKey = byteArrayOf(1),
            payloadNonce = ByteArray(12),
            payloadCiphertext = byteArrayOf(1),
            payloadTag = ByteArray(16),
            clientFeedbackId = "client-key-failure",
            keyId = "unknown-key"
        )
        val fixture = fixture(ingest, EnvelopeCrypto())
        every { fixture.transport.unwrapDataKey(any(), "unknown-key") } throws
            IllegalArgumentException("SDK_TRANSPORT_KEY_ID_UNKNOWN")

        fixture.service.processOne(4)

        assertEquals("RETRY_PENDING", ingest.ingestStatus)
        assertEquals("FAILED", ingest.bodyProcessingStatus)
        assertEquals("NOT_PROVIDED", ingest.contactProcessingStatus)
        assertNotNull(ingest.nextRetryAt)
        assertNull(ingest.feedbackSourceId)
        verify(exactly = 0) { fixture.dirtyMarker.mark(any()) }
    }

    @Test
    fun `dirty marker failure propagates after authoritative writes`() {
        val dataKey = ByteArray(32) { 5 }
        val crypto = EnvelopeCrypto()
        val encrypted = crypto.encrypt("""{"description":"安全正文","business_context":{}}""".toByteArray(), dataKey)
        val ingest = envelope(
            id = 5,
            encryptedDataKey = byteArrayOf(1),
            payloadNonce = encrypted.nonce,
            payloadCiphertext = encrypted.ciphertext,
            payloadTag = encrypted.tag,
            clientFeedbackId = "client-marker-failure",
            keyId = "key-v1"
        )
        val fixture = fixture(ingest, crypto)
        every { fixture.transport.unwrapDataKey(any(), "key-v1") } returns dataKey.copyOf()
        every { fixture.sources.findByRecordId("client-marker-failure") } returns null
        every { fixture.sources.insert(any()) } answers { firstArg<SdkFeedbackSourcePo>().also { it.id = 50 } }
        every { fixture.dirtyMarker.mark(any()) } throws IllegalStateException("DIRTY_MARK_FAILED")

        val error = assertThrows(IllegalStateException::class.java) { fixture.service.processOne(5) }

        assertEquals("DIRTY_MARK_FAILED", error.message)
        assertEquals("COMPLETED", ingest.ingestStatus)
        verify(exactly = 0) {
            fixture.ingests.saveState(match { it.lastErrorCode == "IllegalStateException" })
        }
    }

    @Test
    fun `completed body contact recovery marks identity dirty when identity is materialized`() {
        val dataKey = ByteArray(32) { 6 }
        val crypto = EnvelopeCrypto()
        val contactKey = crypto.derivePurposeKey(dataKey, "contact")
        val encryptedContact = crypto.encrypt("13812345678".toByteArray(), contactKey)
        contactKey.fill(0)
        val ingest = envelope(
            id = 6,
            encryptedDataKey = byteArrayOf(1),
            payloadNonce = ByteArray(12),
            payloadCiphertext = byteArrayOf(1),
            payloadTag = ByteArray(16),
            clientFeedbackId = "client-contact-recovery",
            keyId = "key-v1"
        ).apply {
            bodyProcessingStatus = "PASS"
            feedbackSourceId = 60
            contactAllowed = true
            contactNonce = encryptedContact.nonce
            contactCiphertext = encryptedContact.ciphertext
            contactTag = encryptedContact.tag
        }
        val fixture = fixture(ingest, crypto)
        every { fixture.transport.unwrapDataKey(any(), "key-v1") } returns dataKey.copyOf()
        every { fixture.authorizedContacts.findRetainedByIngestId(6) } returns listOf(
            SdkAuthorizedContactPo(
                ingestId = 6,
                sourceId = 60,
                contactType = "PHONE",
                fingerprint = ByteArray(32) { 1 },
                fingerprintKeyId = "fp-v1",
                authorized = true,
                expiresAt = LocalDateTime.now().plusDays(90)
            )
        )

        fixture.service.processOne(6)

        verify {
            fixture.dirtyMarker.mark(match {
                it.sourceIds == listOf(60L) && it.reason == FeedbackListProjectionDirtyReason.IDENTITY
            })
        }
        verify(exactly = 1) { fixture.identities.findBySourceIds(listOf(60L)) }
    }

    private fun fixture(
        ingest: SdkProcessingEnvelopePo,
        crypto: EnvelopeCrypto,
        failureAlerts: IngestFailureAlertPublisher? = null
    ): Fixture {
        val ingests = mockk<SdkProcessingEnvelopeRepository>()
        val sources = mockk<SdkFeedbackSourceMutationRepository>()
        val contents = mockk<SdkFeedbackContentMutationRepository>()
        val channels = mockk<SdkChannelMutationRepository>()
        val identities = mockk<SdkIdentityMutationRepository>()
        val attachments = mockk<SdkAttachmentMutationRepository>()
        val authorizedContacts = mockk<SdkAuthorizedContactMutationRepository>()
        val dirtyMarker = mockk<FeedbackListProjectionDirtyMarker>()
        val transport = mockk<TransportKeyService>()
        val audit = mockk<MaskingAuditRepository>(relaxed = true)
        every { audit.save(any()) } answers { firstArg() }
        every { ingests.get(ingest.id) } returns ingest
        every { ingests.saveState(any()) } just Runs
        every { contents.upsert(any()) } just Runs
        every { channels.upsert(any()) } just Runs
        every { identities.findBySourceIds(any()) } returns emptyList()
        every { identities.upsertBatch(any()) } just Runs
        every { attachments.linkToSource(any(), any()) } returns 0
        every { authorizedContacts.findRetainedByIngestId(any()) } returns emptyList()
        every { authorizedContacts.linkToSource(any(), any()) } returns 0
        every { authorizedContacts.upsert(any()) } just Runs
        every { dirtyMarker.mark(any()) } just Runs
        val service = SdkSecureProcessingService(
            ingests,
            sources,
            contents,
            channels,
            identities,
            attachments,
            authorizedContacts,
            dirtyMarker,
            transport,
            crypto,
            MaskingPipeline(audit, jacksonObjectMapper(), NoopNerMasker),
            IdentityFingerprintService("fingerprint-secret-that-is-longer-than-32-chars", "fp-v1"),
            ContactStorageCrypto("contact-secret-that-is-longer-than-32-characters", crypto, "contact-v1"),
            jacksonObjectMapper(),
            failureAlerts = failureAlerts
        )
        return Fixture(
            service, ingests, sources, contents, channels, identities, authorizedContacts, dirtyMarker, transport
        )
    }

    private fun envelope(
        id: Long,
        encryptedDataKey: ByteArray,
        payloadNonce: ByteArray,
        payloadCiphertext: ByteArray,
        payloadTag: ByteArray,
        clientFeedbackId: String,
        keyId: String
    ) = SdkProcessingEnvelopePo(
        id = id,
        receiptId = ByteArray(16) { id.toByte() },
        encryptedDataKey = encryptedDataKey,
        payloadNonce = payloadNonce,
        payloadCiphertext = payloadCiphertext,
        payloadTag = payloadTag,
        clientFeedbackId = clientFeedbackId,
        appId = "course",
        packageName = "com.example.course",
        sourceEventTime = LocalDateTime.now(),
        keyId = keyId,
        retentionUntil = LocalDateTime.now().plusDays(7)
    )

    private data class Fixture(
        val service: SdkSecureProcessingService,
        val ingests: SdkProcessingEnvelopeRepository,
        val sources: SdkFeedbackSourceMutationRepository,
        val contents: SdkFeedbackContentMutationRepository,
        val channels: SdkChannelMutationRepository,
        val identities: SdkIdentityMutationRepository,
        val authorizedContacts: SdkAuthorizedContactMutationRepository,
        val dirtyMarker: FeedbackListProjectionDirtyMarker,
        val transport: TransportKeyService
    )
}
