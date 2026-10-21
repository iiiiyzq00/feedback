package com.feedback.model.po.attachment

/** `AVAILABLE` 对象及其待复核后精确删除的本机副本事实。 */
data class AttachmentObjectCleanupCandidatePo(
    val id: Long,
    val storagePath: String,
    val fileSizeBytes: Long,
    val fileSha256: ByteArray,
    val objectBucket: String,
    val objectKey: String,
    val objectVersion: String?,
    val objectEtag: String?,
    val objectStorageStatus: String
)
