package com.feedback.service.sdk

import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.strategy.sdk.*
import com.feedback.security.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*

import com.feedback.service.impl.sdk.SdkIngestServiceImpl
import com.feedback.common.exception.ClientException
import com.feedback.model.sdk.SdkIngest
import com.feedback.repository.sdk.SdkIngestRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class SdkFeedbackProgressTest {
    private val repository = mockk<SdkIngestRepository>()
    private val service = SdkIngestServiceImpl(repository, mockk(), mockk(), DefaultSdkIngestPolicy)

    @Test
    fun `returns safe progress for receipt owned by authenticated app`() {
        val receipt = ByteArray(16) { it.toByte() }
        every { repository.findByReceiptId(match { it.contentEquals(receipt) }) } returns SdkIngest(
            receiptId = receipt,
            clientFeedbackId = "client-1",
            appId = "app-a",
            keyId = "key",
            ingestStatus = "PROCESSING",
            bodyProcessingStatus = "COMPLETED"
        )

        val result = service.feedbackProgress(Base64.getUrlEncoder().withoutPadding().encodeToString(receipt), "app-a")

        assertEquals("PROCESSING", result.status)
        assertEquals("STANDARDIZATION", result.stage)
        assertEquals(75, result.progressPercent)
    }

    @Test
    fun `does not allow another app to query an opaque receipt`() {
        val receipt = ByteArray(16) { 1 }
        every { repository.findByReceiptId(any()) } returns SdkIngest(
            receiptId = receipt, clientFeedbackId = "client-1", appId = "app-a", keyId = "key"
        )

        val error = assertThrows(ClientException::class.java) {
            service.feedbackProgress(Base64.getUrlEncoder().withoutPadding().encodeToString(receipt), "app-b")
        }

        assertEquals("SDK_RECEIPT_FORBIDDEN", error.errorCode.name)
    }
}
