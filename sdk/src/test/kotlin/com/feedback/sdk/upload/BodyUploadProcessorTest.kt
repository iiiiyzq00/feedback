package com.feedback.sdk.upload

import com.feedback.sdk.db.dao.AttachmentDao
import com.feedback.sdk.db.dao.FeedbackDao
import com.feedback.sdk.db.entity.PendingFeedback
import com.feedback.sdk.network.FeedbackApi
import com.feedback.sdk.network.models.ApiResponseWrapper
import com.feedback.sdk.network.models.FeedbackAck
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class BodyUploadProcessorTest {
    private val feedbackDao = mockk<FeedbackDao>(relaxed = true)
    private val attachmentDao = mockk<AttachmentDao>()
    private val api = mockk<FeedbackApi>()

    @Test
    fun `successful ack advances persisted body and schedules attachments`() = runTest {
        val feedback = pending()
        prepare(feedback)
        coEvery { api.submitFeedback(any()) } returns Response.success(
            ApiResponseWrapper(0, "OK", FeedbackAck(feedback.clientFeedbackId, "receipt-1", "ENCRYPTED_ACCEPTED"))
        )
        val accepted = mutableListOf<String>()

        val retry = processor(onAccepted = accepted::add).processPending()

        assertFalse(retry)
        coVerify { feedbackDao.update(match { it.status == "PENDING_ATTACHMENTS" && it.receiptId == "receipt-1" }) }
        assertTrue(accepted == listOf(feedback.clientFeedbackId))
    }

    @Test
    fun `http success without encrypted accepted status is not presented as accepted`() = runTest {
        val feedback = pending()
        prepare(feedback)
        coEvery { api.submitFeedback(any()) } returns Response.success(
            ApiResponseWrapper(0, "OK", FeedbackAck(feedback.clientFeedbackId, "receipt-1", "PROCESSING"))
        )
        val accepted = mutableListOf<String>()

        assertFalse(processor(onAccepted = accepted::add).processPending())

        coVerify { feedbackDao.update(match {
            it.status == "PERMANENT_FAILED" && it.lastErrorCode == "ACK_STATUS_INVALID"
        }) }
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun `server error remains pending and requests work retry`() = runTest {
        val feedback = pending(retryCount = 2)
        prepare(feedback)
        coEvery { api.submitFeedback(any()) } returns httpError(500)

        assertTrue(processor().processPending())
        coVerify { feedbackDao.update(match {
            it.status == "PENDING_BODY" && it.retryCount == 3 && it.lastErrorCode == "HTTP_500"
        }) }
    }

    @Test
    fun `rate limit remains retryable`() = runTest {
        val feedback = pending()
        prepare(feedback)
        coEvery { api.submitFeedback(any()) } returns httpError(429)

        assertTrue(processor().processPending())
        coVerify { feedbackDao.update(match { it.status == "PENDING_BODY" && it.lastErrorCode == "HTTP_429" }) }
    }

    @Test
    fun `client error becomes permanent failure without retry`() = runTest {
        val feedback = pending()
        prepare(feedback)
        coEvery { api.submitFeedback(any()) } returns httpError(400)

        assertFalse(processor().processPending())
        coVerify { feedbackDao.update(match { it.status == "PERMANENT_FAILED" && it.lastErrorCode == "HTTP_400" }) }
    }

    @Test
    fun `eighth transient failure is retained for explicit historical retry`() = runTest {
        val feedback = pending(retryCount = 7)
        prepare(feedback)
        coEvery { api.submitFeedback(any()) } throws IOException("offline")

        assertFalse(processor().processPending())
        coVerify { feedbackDao.update(match {
            it.status == "RETRY_EXHAUSTED" && it.retryCount == 8 && it.lastErrorCode == "IOException"
        }) }
    }

    @Test
    fun `new processor instance resumes body persisted before app restart`() = runTest {
        val feedback = pending(retryCount = 1)
        prepare(feedback)
        coEvery { api.submitFeedback(any()) } returns Response.success(
            ApiResponseWrapper(0, "OK", FeedbackAck(feedback.clientFeedbackId, "receipt-after-restart", "ENCRYPTED_ACCEPTED"))
        )

        assertFalse(processor().processPending())
        coVerify { feedbackDao.getPendingBodies(20) }
        coVerify { feedbackDao.update(match { it.receiptId == "receipt-after-restart" }) }
    }

    @Test
    fun `retry preserves the transport key identifier used to wrap the persisted body`() = runTest {
        val feedback = pending().copy(transportKeyId = "sdk-transport-v9")
        val request = slot<com.feedback.sdk.network.models.FeedbackRequest>()
        prepare(feedback)
        coEvery { api.submitFeedback(capture(request)) } returns Response.success(
            ApiResponseWrapper(0, "OK", FeedbackAck(feedback.clientFeedbackId, "receipt-1", "ENCRYPTED_ACCEPTED"))
        )

        processor().processPending()

        assertEquals("sdk-transport-v9", request.captured.encryptedPayload.keyId)
    }

    private fun prepare(feedback: PendingFeedback) {
        coEvery { feedbackDao.getPendingBodies(20) } returns listOf(feedback)
        coEvery { attachmentDao.getByFeedbackId(feedback.clientFeedbackId) } returns emptyList()
    }

    private fun processor(onAccepted: (String) -> Unit = {}) = BodyUploadProcessor(
        feedbackDao, attachmentDao, api, onAccepted
    )

    private fun httpError(code: Int): Response<ApiResponseWrapper<FeedbackAck>> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaType()))

    private fun pending(retryCount: Int = 0) = PendingFeedback(
        clientFeedbackId = "client-1",
        packageName = "com.example.app",
        appVersion = "1.0",
        functionModule = "course",
        issueType = "功能异常",
        sourceEventTime = 1_700_000_000_000,
        osVersion = "16",
        networkType = "WIFI",
        triggerType = "CODE",
        encryptedDataKey = byteArrayOf(1, 2, 3),
        payloadNonce = byteArrayOf(4, 5, 6),
        payloadCiphertext = byteArrayOf(7, 8, 9),
        payloadTag = byteArrayOf(10, 11, 12),
        retryCount = retryCount
    )
}
