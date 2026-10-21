package com.feedback.audit

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 不受信任自由文本的审计摘要。
 *
 * 审计和短期授权记录只保存规范化文本的 SHA-256 与字符数，不保存调用方原文。摘要用于关联和
 * 重复输入核对，不能恢复原文，也不能代替业务用途码。
 */
data class AuditTextDigest(
    val sha256: String,
    val length: Int
) {
    /** 适用于必须保留非空原因字段的持久化安全表示，长度固定落在现有 3～200 字约束内。 */
    fun storageValue(): String = "sha256:$sha256;length:$length"
}

object AuditTextDigester {
    fun digest(value: String): AuditTextDigest {
        val normalized = value.trim()
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AuditTextDigest(sha256, normalized.length)
    }
}
