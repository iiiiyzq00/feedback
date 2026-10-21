package com.feedback.model.attachment

/** 加密日志预览值；密文可持久化，明文长度用于读取时完整性复核。 */
data class EncryptedLogPreview(
    val ciphertext: ByteArray,
    val plaintextBytes: Int
)

/** 有界 JPEG 派生预览；尺寸与字节数均已通过生成器校验。 */
data class GeneratedMediaPreview(
    val bytes: ByteArray,
    val width: Int,
    val height: Int
)

/** 本地副本清理结果的稳定内部集合。 */
enum class AttachmentLocalCleanupResult {
    DISABLED,
    NOT_PENDING,
    DELETED
}

/** 对象发布阶段的稳定内部结果。 */
enum class AttachmentObjectPublishResult {
    DISABLED,
    NOT_ELIGIBLE,
    PUBLISHED,
    ALREADY_PUBLISHED
}
