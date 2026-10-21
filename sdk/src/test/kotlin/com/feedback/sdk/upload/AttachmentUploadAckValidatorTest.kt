package com.feedback.sdk.upload

import com.feedback.sdk.network.models.ApiResponseWrapper
import com.feedback.sdk.network.models.AttachmentAck
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AttachmentUploadAckValidatorTest {
    @Test
    fun `accepts only matching uploaded server receipt`() {
        val response = Response.success(ApiResponseWrapper(0, "OK", AttachmentAck("attachment-1", "UPLOADED")))
        assertTrue(AttachmentUploadAckValidator.isAccepted(response, "attachment-1"))
    }

    @Test
    fun `rejects mismatched attachment receipt`() {
        val response = Response.success(ApiResponseWrapper(0, "OK", AttachmentAck("attachment-2", "UPLOADED")))
        assertFalse(AttachmentUploadAckValidator.isAccepted(response, "attachment-1"))
    }

    @Test
    fun `rejects successful HTTP with missing or incomplete business ack`() {
        assertFalse(AttachmentUploadAckValidator.isAccepted(Response.success(ApiResponseWrapper(0, "OK", null)), "attachment-1"))
        assertFalse(AttachmentUploadAckValidator.isAccepted(
            Response.success(ApiResponseWrapper(0, "OK", AttachmentAck("attachment-1", "PENDING"))),
            "attachment-1"
        ))
    }

    @Test
    fun `rejects HTTP errors`() {
        val response = Response.error<ApiResponseWrapper<AttachmentAck>>(503, "unavailable".toResponseBody())
        assertFalse(AttachmentUploadAckValidator.isAccepted(response, "attachment-1"))
    }
}
