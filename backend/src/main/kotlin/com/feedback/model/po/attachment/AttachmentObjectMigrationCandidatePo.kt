package com.feedback.model.po.attachment

/** 已通过安全校验、可幂等发布到对象存储的本机附件事实。 */
data class AttachmentObjectMigrationCandidatePo(
    val id: Long,
    val sourceKind: String,
    val storagePath: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val fileSha256: ByteArray,
    val objectStorageStatus: String? = null
)
