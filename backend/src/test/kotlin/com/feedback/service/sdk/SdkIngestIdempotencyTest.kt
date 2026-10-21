package com.feedback.service.sdk

import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.strategy.sdk.*
import com.feedback.security.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*

import com.feedback.service.impl.sdk.SdkIngestServiceImpl
import com.feedback.common.exception.ClientException
import com.feedback.dto.sdk.EncryptedPayloadDto
import com.feedback.dto.sdk.SdkFeedbackRequest
import com.feedback.model.sdk.SdkIngest
import com.feedback.repository.sdk.SdkIngestRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class SdkIngestIdempotencyTest {
    @Test
    fun `unsupported SDK schema fails closed before authentication or persistence`() {
        val repository = mockk<SdkIngestRepository>(relaxed = true)
        val authenticator = mockk<SdkRequestAuthenticator>(relaxed = true)
        val persistence = mockk<SdkIngestPersistence>(relaxed = true)
        val service = SdkIngestServiceImpl(repository, authenticator, persistence, DefaultSdkIngestPolicy)
        val request = request("new-1", schemaVersion = "sdk-form-v999")

        val error = assertThrows(ClientException::class.java) {
            service.ingestFeedback(request, "course", "signature", "1", "nonce", "POST", "/api/v1/sdk/feedback", "{}".toByteArray(), "127.0.0.1")
        }

        assertEquals("SDK_SCHEMA_VERSION_UNSUPPORTED", error.errorCode.name)
        verify(exactly = 0) { authenticator.authenticate(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { persistence.saveReceived(any()) }
    }

    @Test
    fun `replayed client feedback id returns original receipt without duplicate write`() {
        val repository = mockk<SdkIngestRepository>()
        val authenticator = mockk<SdkRequestAuthenticator>()
        val persistence = mockk<SdkIngestPersistence>()
        val receipt = ByteArray(16) { it.toByte() }
        every { authenticator.authenticate(any(), any(), any(), any(), any(), any(), any(), any()) } returns Unit
        every { repository.findByAppIdAndClientFeedbackId("course", "historical-1") } returns SdkIngest(
            id = 1,
            receiptId = receipt,
            appId = "course",
            clientFeedbackId = "historical-1",
            keyId = "key",
            requestSha256 = java.security.MessageDigest.getInstance("SHA-256").digest("{}".toByteArray())
        )
        val service = SdkIngestServiceImpl(repository, authenticator, persistence, DefaultSdkIngestPolicy)

        val ack = service.ingestFeedback(
            request("historical-1"), "course", "signature", "1", "nonce-2", "POST",
            "/api/v1/sdk/feedback", "{}".toByteArray(), "127.0.0.1"
        )

        assertEquals("historical-1", ack.clientFeedbackId)
        assertEquals("ENCRYPTED_ACCEPTED", ack.status)
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(receipt), ack.receiptId)
        verify(exactly = 0) { persistence.saveReceived(any()) }
    }

    @Test
    fun `same client feedback id with different command fingerprint is rejected`() {
        val repository = mockk<SdkIngestRepository>()
        val authenticator = mockk<SdkRequestAuthenticator>(relaxed = true)
        val persistence = mockk<SdkIngestPersistence>(relaxed = true)
        every { repository.findByAppIdAndClientFeedbackId("course", "historical-2") } returns SdkIngest(
            id = 2,
            receiptId = ByteArray(16),
            appId = "course",
            clientFeedbackId = "historical-2",
            keyId = "key",
            requestSha256 = ByteArray(32) { 7 }
        )
        val service = SdkIngestServiceImpl(repository, authenticator, persistence, DefaultSdkIngestPolicy)

        val error = assertThrows(ClientException::class.java) {
            service.ingestFeedback(
                request("historical-2"), "course", "signature", "1", "nonce-3", "POST",
                "/api/v1/sdk/feedback", "different-command".toByteArray(), "127.0.0.1"
            )
        }

        assertEquals("SDK_IDEMPOTENT_CONFLICT", error.reasonCode)
        verify(exactly = 0) { persistence.saveReceived(any()) }
    }

    private fun request(clientFeedbackId: String, schemaVersion: String = "sdk-form-v1"): SdkFeedbackRequest {
        val value = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        return SdkFeedbackRequest(
            clientFeedbackId, "com.example", "1.0", "AI_TUTOR", "BUG", "2026-08-04T10:00:00Z",
            "Android 10", "WIFI", "RETRY",
            EncryptedPayloadDto("key", "AES-256-GCM+RSA-OAEP-256", value, value, value, value),
            null,
            schemaVersion = schemaVersion
        )
    }
}
