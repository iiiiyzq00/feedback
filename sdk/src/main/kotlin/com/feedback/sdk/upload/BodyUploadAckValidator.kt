package com.feedback.sdk.upload

import com.feedback.sdk.network.models.ApiResponseWrapper
import com.feedback.sdk.network.models.FeedbackAck
import retrofit2.Response

internal object BodyUploadAckValidator {
    fun accepted(response: Response<ApiResponseWrapper<FeedbackAck>>, expectedClientFeedbackId: String): FeedbackAck? {
        if (!response.isSuccessful) return null
        val envelope=response.body()?:return null
        val ack=envelope.data?:return null
        return ack.takeIf {
            envelope.code in setOf(0,200) &&
                it.clientFeedbackId==expectedClientFeedbackId &&
                it.receiptId.isNotBlank() && it.status=="ENCRYPTED_ACCEPTED"
        }
    }
}
