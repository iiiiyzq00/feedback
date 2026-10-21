package com.feedback.sdk.upload

import com.feedback.sdk.network.models.ApiResponseWrapper
import com.feedback.sdk.network.models.AttachmentAck
import retrofit2.Response

internal object AttachmentUploadAckValidator {
    private val acceptedStatuses = setOf("UPLOADED", "VERIFIED")

    fun isAccepted(response: Response<ApiResponseWrapper<AttachmentAck>>, expectedAttachmentId: String): Boolean {
        if (!response.isSuccessful) return false
        val envelope = response.body() ?: return false
        val ack = envelope.data ?: return false
        return envelope.code in setOf(0, 200) &&
            ack.clientAttachmentId == expectedAttachmentId &&
            ack.status in acceptedStatuses
    }
}
