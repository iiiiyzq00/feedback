package com.feedback.model.po.attachment

/** 媒体派生预览后台任务的 MyBatis 投影；不携带原始路径或对象键。 */
data class AttachmentMediaPreviewCandidatePo(
    var id: Long = 0,
    var attachmentType: String = "",
    var mimeType: String? = null,
    var fileSizeBytes: Long? = null,
    var attempts: Int = 0,
)
