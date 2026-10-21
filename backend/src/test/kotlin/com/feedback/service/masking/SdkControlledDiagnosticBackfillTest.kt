package com.feedback.service.masking

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.po.sdk.SdkChannelPo
import com.feedback.model.po.sdk.SdkProcessingEnvelopePo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.repository.sdk.SdkChannelMutationRepository
import com.feedback.repository.sdk.SdkIdentityMutationRepository
import com.feedback.repository.sdk.SdkProcessingEnvelopeRepository
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import com.feedback.security.sdk.TransportKeyService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.NoSuchFileException
import javax.crypto.BadPaddingException

class SdkControlledDiagnosticBackfillTest {
    @Test
    fun `batch writes safe diagnostic and identity facts before marking channel dirty`() {
        val dataKey = ByteArray(32) { 3 }
        val crypto = EnvelopeCrypto()
        val encrypted = crypto.encrypt(
            """{"business_context":{"device_sn":"SN-001","user_id":"USER-001"}}""".toByteArray(),
            dataKey
        )
        val ingests = mockk<SdkProcessingEnvelopeRepository>()
        val identities = mockk<SdkIdentityMutationRepository>()
        val channels = mockk<SdkChannelMutationRepository>()
        val transport = mockk<TransportKeyService>()
        val dirty = mockk<FeedbackListProjectionDirtyMarker>()
        every { ingests.findBySourceIds(listOf(7L)) } returns listOf(
            SdkProcessingEnvelopePo(
                id = 1,
                feedbackSourceId = 7,
                encryptedDataKey = byteArrayOf(1),
                payloadCiphertext = encrypted.ciphertext,
                payloadNonce = encrypted.nonce,
                payloadTag = encrypted.tag,
                keyId = "key-v1"
            )
        )
        every { identities.findBySourceIds(listOf(7L)) } returns emptyList()
        every { transport.unwrapDataKey(any(), "key-v1") } returns dataKey.copyOf()
        every { channels.upsertControlledDiagnostics(any()) } just Runs
        every { identities.upsertBatch(any()) } just Runs
        every { dirty.mark(any()) } just Runs
        val service = service(ingests, identities, channels, transport, crypto, dirty)

        val completed = service.process(
            listOf(SdkChannelPo(sourceId = 7, clientFeedbackId = "client-7", appId = "app", appInternalLogs = "safe-log"))
        )

        assertEquals(1, completed)
        verifyOrder {
            channels.upsertControlledDiagnostics(match { it.single().diagnosticTypes == "app_internal_logs" })
            identities.upsertBatch(match { rows -> rows.map { it.identityType }.toSet() == setOf("DEVICE_SN", "USER_ID") })
            dirty.mark(match { it.sourceIds == listOf(7L) && it.reason == FeedbackListProjectionDirtyReason.CHANNEL })
        }
    }

    @Test
    fun `batch refuses more than 200 source rows`() {
        val service = service(mockk(), mockk(), mockk(), mockk(), EnvelopeCrypto(), mockk())
        val error = assertThrows(ClientException::class.java) {
            service.process((1L..201L).map { SdkChannelPo(sourceId = it) })
        }
        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("SDK_DIAGNOSTIC_BACKFILL_BATCH_INVALID", error.reasonCode)
    }

    @Test
    fun `runner advances a stable keyset in 200 row pages`() {
        val channels = mockk<SdkChannelMutationRepository>()
        val batches = mockk<SdkControlledDiagnosticBackfillBatchService>()
        val first = (1L..200L).map { SdkChannelPo(sourceId = it) }
        val second = listOf(SdkChannelPo(sourceId = 201))
        every { channels.findPageAfterId(0, 200) } returns first
        every { channels.findPageAfterId(200, 200) } returns second
        every { channels.findPageAfterId(201, 200) } returns emptyList()
        every { batches.process(first) } returns 200
        every { batches.process(second) } returns 1

        assertEquals(201, SdkControlledDiagnosticBackfillRunner(channels, batches).backfill())
        verifySequence {
            channels.findPageAfterId(0, 200)
            batches.process(first)
            channels.findPageAfterId(200, 200)
            batches.process(second)
            channels.findPageAfterId(201, 200)
        }
    }

    @Test
    fun `marker failure propagates after batch writes`() {
        val ingests = mockk<SdkProcessingEnvelopeRepository>()
        val identities = mockk<SdkIdentityMutationRepository>()
        val channels = mockk<SdkChannelMutationRepository>()
        val dirty = mockk<FeedbackListProjectionDirtyMarker>()
        every { ingests.findBySourceIds(listOf(9L)) } returns emptyList()
        every { identities.findBySourceIds(listOf(9L)) } returns emptyList()
        every { channels.upsertControlledDiagnostics(any()) } just Runs
        every { identities.upsertBatch(any()) } just Runs
        every { dirty.mark(any()) } throws IllegalStateException("DIRTY_MARK_FAILED")
        val service = service(ingests, identities, channels, mockk(), EnvelopeCrypto(), dirty)

        val error = assertThrows(IllegalStateException::class.java) {
            service.process(listOf(SdkChannelPo(sourceId = 9, appInternalLogs = "safe-log")))
        }

        assertEquals("DIRTY_MARK_FAILED", error.message)
        verifyOrder {
            channels.upsertControlledDiagnostics(any())
            dirty.mark(any())
        }
        verify(exactly = 0) { identities.upsertBatch(any()) }
    }

    @Test
    fun `startup backfill requires explicit opt in`() {
        val condition = SdkControlledDiagnosticBackfillRunner::class.java
            .getAnnotation(ConditionalOnProperty::class.java)

        assertEquals(listOf("app.sdk.controlled-diagnostic-backfill-enabled"), condition.name.toList())
        assertEquals("true", condition.havingValue)
        assertEquals(false, condition.matchIfMissing)
    }

    @Test
    fun `non payload preparation failure aborts the batch`() {
        val ingests = mockk<SdkProcessingEnvelopeRepository>()
        val identities = mockk<SdkIdentityMutationRepository>()
        val channels = mockk<SdkChannelMutationRepository>()
        val contacts = mockk<ContactStorageCrypto>()
        val dirty = mockk<FeedbackListProjectionDirtyMarker>(relaxed = true)
        val failure = IllegalStateException("CONTACT_ENCRYPT_FAILED")
        every { ingests.findBySourceIds(listOf(11L)) } returns emptyList()
        every { identities.findBySourceIds(listOf(11L)) } returns emptyList()
        every { contacts.encrypt(any()) } throws failure
        val service = service(ingests, identities, channels, mockk(), EnvelopeCrypto(), dirty, contacts)

        val thrown = assertThrows(IllegalStateException::class.java) {
            service.process(listOf(SdkChannelPo(sourceId = 11, appInternalLogs = "safe-log")))
        }

        assertSame(failure, thrown)
        verify(exactly = 0) { channels.upsertControlledDiagnostics(any()) }
        verify(exactly = 0) { identities.upsertBatch(any()) }
        verify(exactly = 0) { dirty.mark(any()) }
    }

    @Test
    fun `runner does not advance keyset when a batch fails`() {
        val channels = mockk<SdkChannelMutationRepository>()
        val batches = mockk<SdkControlledDiagnosticBackfillBatchService>()
        val page = listOf(SdkChannelPo(sourceId = 12))
        every { channels.findPageAfterId(0, 200) } returns page
        every { batches.process(page) } throws IllegalStateException("BATCH_FAILED")

        assertThrows(IllegalStateException::class.java) {
            SdkControlledDiagnosticBackfillRunner(channels, batches).backfill()
        }
        verify(exactly = 1) { channels.findPageAfterId(0, 200) }
        verify(exactly = 0) { channels.findPageAfterId(12, 200) }
    }

    @Test
    fun `transport key configuration failure aborts batch instead of skipping source`() {
        val ingests = mockk<SdkProcessingEnvelopeRepository>()
        val identities = mockk<SdkIdentityMutationRepository>()
        val channels = mockk<SdkChannelMutationRepository>()
        val transport = mockk<TransportKeyService>()
        val dirty = mockk<FeedbackListProjectionDirtyMarker>(relaxed = true)
        val failure = NoSuchFileException("missing-sdk-private-key.pem")
        every { ingests.findBySourceIds(listOf(13L)) } returns listOf(
            encryptedEnvelope(sourceId = 13)
        )
        every { identities.findBySourceIds(listOf(13L)) } returns emptyList()
        every { transport.unwrapDataKey(any(), "key-v1") } throws failure
        val service = service(ingests, identities, channels, transport, EnvelopeCrypto(), dirty)

        val thrown = assertThrows(NoSuchFileException::class.java) {
            service.process(listOf(SdkChannelPo(sourceId = 13)))
        }

        assertSame(failure, thrown)
        verify(exactly = 0) { channels.upsertControlledDiagnostics(any()) }
        verify(exactly = 0) { identities.upsertBatch(any()) }
        verify(exactly = 0) { dirty.mark(any()) }
    }

    @Test
    fun `ambiguous RSA unwrap failure aborts because wrong private key cannot be excluded`() {
        val ingests = mockk<SdkProcessingEnvelopeRepository>()
        val identities = mockk<SdkIdentityMutationRepository>()
        val channels = mockk<SdkChannelMutationRepository>()
        val transport = mockk<TransportKeyService>()
        val dirty = mockk<FeedbackListProjectionDirtyMarker>(relaxed = true)
        val failure = BadPaddingException("wrong private key or corrupt wrapped key")
        every { ingests.findBySourceIds(listOf(16L)) } returns listOf(encryptedEnvelope(sourceId = 16))
        every { identities.findBySourceIds(listOf(16L)) } returns emptyList()
        every { transport.unwrapDataKey(any(), "key-v1") } throws failure
        val service = service(ingests, identities, channels, transport, EnvelopeCrypto(), dirty)

        val thrown = assertThrows(BadPaddingException::class.java) {
            service.process(listOf(SdkChannelPo(sourceId = 16)))
        }

        assertSame(failure, thrown)
        verify(exactly = 0) { channels.upsertControlledDiagnostics(any()) }
        verify(exactly = 0) { identities.upsertBatch(any()) }
        verify(exactly = 0) { dirty.mark(any()) }
    }

    @Test
    fun `authenticated payload corruption is the only decrypt failure skipped per source`() {
        val ingests = mockk<SdkProcessingEnvelopeRepository>()
        val identities = mockk<SdkIdentityMutationRepository>()
        val channels = mockk<SdkChannelMutationRepository>()
        val transport = mockk<TransportKeyService>()
        val dirty = mockk<FeedbackListProjectionDirtyMarker>(relaxed = true)
        every { ingests.findBySourceIds(listOf(14L)) } returns listOf(
            encryptedEnvelope(sourceId = 14, payloadTag = ByteArray(16) { 99 })
        )
        every { identities.findBySourceIds(listOf(14L)) } returns emptyList()
        every { transport.unwrapDataKey(any(), "key-v1") } returns ByteArray(32) { 3 }
        val service = service(ingests, identities, channels, transport, EnvelopeCrypto(), dirty)

        assertEquals(0, service.process(listOf(SdkChannelPo(sourceId = 14))))

        verify(exactly = 0) { channels.upsertControlledDiagnostics(any()) }
        verify(exactly = 0) { identities.upsertBatch(any()) }
        verify(exactly = 0) { dirty.mark(any()) }
    }

    @Test
    fun `malformed historical JSON is skipped as a data format error`() {
        val dataKey = ByteArray(32) { 4 }
        val crypto = EnvelopeCrypto()
        val encrypted = crypto.encrypt("{not-json".toByteArray(), dataKey)
        val ingests = mockk<SdkProcessingEnvelopeRepository>()
        val identities = mockk<SdkIdentityMutationRepository>()
        val channels = mockk<SdkChannelMutationRepository>()
        val transport = mockk<TransportKeyService>()
        val dirty = mockk<FeedbackListProjectionDirtyMarker>(relaxed = true)
        every { ingests.findBySourceIds(listOf(15L)) } returns listOf(
            encryptedEnvelope(sourceId = 15).copy(
                payloadCiphertext = encrypted.ciphertext,
                payloadNonce = encrypted.nonce,
                payloadTag = encrypted.tag
            )
        )
        every { identities.findBySourceIds(listOf(15L)) } returns emptyList()
        every { transport.unwrapDataKey(any(), "key-v1") } returns dataKey.copyOf()
        val service = service(ingests, identities, channels, transport, crypto, dirty)

        assertEquals(0, service.process(listOf(SdkChannelPo(sourceId = 15))))

        verify(exactly = 0) { channels.upsertControlledDiagnostics(any()) }
        verify(exactly = 0) { identities.upsertBatch(any()) }
        verify(exactly = 0) { dirty.mark(any()) }
    }

    private fun service(
        ingests: SdkProcessingEnvelopeRepository,
        identities: SdkIdentityMutationRepository,
        channels: SdkChannelMutationRepository,
        transport: TransportKeyService,
        crypto: EnvelopeCrypto,
        dirty: FeedbackListProjectionDirtyMarker,
        contacts: ContactStorageCrypto = ContactStorageCrypto(
            "contact-secret-that-is-longer-than-32-characters", crypto, "contact-v1"
        )
    ) = SdkControlledDiagnosticBackfillBatchService(
        ingests,
        identities,
        channels,
        transport,
        crypto,
        contacts,
        IdentityFingerprintService("fingerprint-secret-that-is-longer-than-32-chars", "fp-v1"),
        jacksonObjectMapper(),
        dirty
    )

    private fun encryptedEnvelope(
        sourceId: Long,
        payloadTag: ByteArray = ByteArray(16)
    ) = SdkProcessingEnvelopePo(
        id = sourceId,
        feedbackSourceId = sourceId,
        encryptedDataKey = byteArrayOf(1),
        payloadCiphertext = byteArrayOf(1),
        payloadNonce = ByteArray(12),
        payloadTag = payloadTag,
        keyId = "key-v1"
    )
}
