package com.feedback.sdk.upload

import com.feedback.sdk.network.models.ApiResponseWrapper
import com.feedback.sdk.network.models.FeedbackAck
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class BodyUploadAckValidatorTest {
    @Test fun `accepts matching encrypted receipt`() {
        val ack=FeedbackAck("client-1","receipt-1","ENCRYPTED_ACCEPTED")
        assertEquals(ack,BodyUploadAckValidator.accepted(Response.success(ApiResponseWrapper(200,"OK",ack)),"client-1"))
    }

    @Test fun `rejects mismatched id status empty receipt and http error`() {
        fun response(ack:FeedbackAck)=Response.success(ApiResponseWrapper(200,"OK",ack))
        assertNull(BodyUploadAckValidator.accepted(response(FeedbackAck("other","receipt","ENCRYPTED_ACCEPTED")),"client-1"))
        assertNull(BodyUploadAckValidator.accepted(response(FeedbackAck("client-1","receipt","PROCESSING")),"client-1"))
        assertNull(BodyUploadAckValidator.accepted(response(FeedbackAck("client-1","","ENCRYPTED_ACCEPTED")),"client-1"))
        assertNull(BodyUploadAckValidator.accepted(Response.error(500,"{}".toResponseBody("application/json".toMediaType())),"client-1"))
    }
}
