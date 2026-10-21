package com.feedback.model.po.attachment

data class AttachmentScanCandidatePo(
    val id: Long,
    val storagePath: String,
    val mimeType: String?,
    val attachmentType: String = "",
    val sourceAttachmentId: String? = null
)
