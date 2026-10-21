package com.feedback.model.imports

import java.time.LocalDateTime

/** 导入附件处理任务；密文 URL、租约与重试字段共同约束安全下载状态机。 */
data class ImportAttachmentTask(
    var id: Long = 0,
    val importRowId: Long,
    val feedbackAttachmentId: Long? = null,
    val sourceUrlCiphertext: ByteArray?,
    val sourceUrlNonce: ByteArray?,
    val sourceUrlTag: ByteArray?,
    val sourceUrlKeyId: String?,
    val sourceUrlAlgorithm: String? = "AES-256-GCM",
    val urlExpiresAt: LocalDateTime,
    val sourceUrlHash: ByteArray,
    val urlDomain: String,
    val attachmentType: String,
    val expectedSha256: ByteArray? = null,
    val expectedSizeBytes: Long? = null,
    val taskStatus: String = "PENDING",
    val finalStatus: String? = null,
    val retryCount: Int = 0,
    val nextRetryAt: LocalDateTime? = null,
    val lastError: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
