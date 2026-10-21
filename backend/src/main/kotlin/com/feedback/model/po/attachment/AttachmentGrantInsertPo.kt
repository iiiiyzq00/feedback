package com.feedback.model.po.attachment

import java.time.LocalDateTime

/**
 * 新建授权的持久化参数；fingerprint 固定为原始令牌的 SHA-256，accessReason 固定为原因摘要/长度，
 * 两者都不得替换为调用方原文。
 */
data class AttachmentGrantInsertPo(
    val sourceId: Long,
    val attachmentId: Long,
    val userId: Long,
    val fingerprint: ByteArray,
    val expiresAt: LocalDateTime,
    val accessReason: String
)
