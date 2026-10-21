package com.feedback.service.sdk

import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.strategy.sdk.*
import com.feedback.security.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*

import com.feedback.service.impl.sdk.SdkAttachmentServiceImpl
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.common.exception.ClientException
import com.feedback.model.sdk.SdkIngest
import com.feedback.repository.sdk.SdkIngestRepository
import com.feedback.repository.attachment.FeedbackAttachmentMutationRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Path
import java.util.Base64

class SdkAttachmentAuthorizationTest {
    @TempDir lateinit var root: Path
    private val ingests = mockk<SdkIngestRepository>()
    private val service by lazy {
        SdkAttachmentServiceImpl(
            ingests,
            mockk<FeedbackAttachmentMutationRepository>(),
            jacksonObjectMapper(),
            mockk<SdkAttachmentDecryptor>(),
            DefaultSdkIngestPolicy,
            root.toString()
        )
    }

    @Test
    fun `rejects attachment when authenticated app does not own receipt`() {
        assertReceiptOwnerRejected(Base64.getUrlEncoder().withoutPadding())
    }

    @Test
    fun `accepts legacy standard base64 receipt while authorizing owner`() {
        assertReceiptOwnerRejected(Base64.getEncoder())
    }

    private fun assertReceiptOwnerRejected(encoder: Base64.Encoder) {
        val receipt = ByteArray(16) { 0xff.toByte() }
        every { ingests.findByReceiptId(match { it.contentEquals(receipt) }) } returns SdkIngest(
            id = 7, receiptId = receipt, clientFeedbackId = "client-1", appId = "course", keyId = "key"
        )

        val error = assertThrows(ClientException::class.java) {
            service.ingestAttachment(
                encoder.withoutPadding().encodeToString(receipt),
                "question-bank",
                "{}",
                MockMultipartFile("encrypted_file", "payload.enc", "application/octet-stream", byteArrayOf(1))
            )
        }

        assertEquals("SDK_RECEIPT_FORBIDDEN", error.errorCode.name)
    }
}
