package com.feedback.sdk.capture

/** 明文附件只留在内存里，直到提交时加密写入应用私有存储。 */
data class AttachmentInput(
    val type: String,
    val mimeType: String,
    val bytes: ByteArray,
    val parentAttachmentId: String? = null,
    val annotationJson: String? = null
)
