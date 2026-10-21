package com.feedback.application.port.feedback.attachment

import com.feedback.common.*
import java.security.MessageDigest
import java.time.LocalDateTime

/** 附件列表可用性计算所需的单表事实，不包含 PO、存储路径或内容。 */
data class AttachmentListFacts(
    val id: Long,
    val sourceId: Long,
    val attachmentType: AttachmentType?,
    val mimeType: String?,
    val fileSizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val durationSeconds: Int?,
    val uploadStatus: AttachmentUploadStatus?,
    val securityStatus: AttachmentSecurityStatus?,
    val integrityStatus: AttachmentIntegrityStatus?,
    val decryptionStatus: AttachmentDecryptionStatus?,
    val malwareScanStatus: AttachmentMalwareScanStatus?,
    val contentReviewStatus: AttachmentContentReviewStatus?,
    val sourceKind: AttachmentSourceKind?,
    val retentionUntil: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val deletedAt: LocalDateTime?,
    val storedContentPresent: Boolean,
    /** 私有派生预览是否已经生成；只公开状态，不公开对象键。 */
    val mediaPreviewStatus: AttachmentPreviewStatus? = null,
    val queryTime: LocalDateTime
)

/** 授权与消费时实时复核的最小附件事实。 */
data class AttachmentAuthorizationFacts(
    val id: Long,
    val sourceId: Long,
    val attachmentType: AttachmentType?,
    val securityStatus: AttachmentSecurityStatus?,
    val integrityStatus: AttachmentIntegrityStatus?,
    val decryptionStatus: AttachmentDecryptionStatus?,
    val malwareScanStatus: AttachmentMalwareScanStatus?,
    val contentReviewStatus: AttachmentContentReviewStatus?,
    val mediaPreviewStatus: AttachmentPreviewStatus? = null,
    val deletedAt: LocalDateTime?
) {
    /** 六项实时门禁必须全部满足；未知状态同样失败关闭。 */
    fun isViewable(): Boolean =
        securityStatus == AttachmentSecurityStatus.VERIFIED &&
            integrityStatus == AttachmentIntegrityStatus.VERIFIED &&
            decryptionStatus in VIEWABLE_DECRYPTION_STATUSES &&
            malwareScanStatus == AttachmentMalwareScanStatus.CLEAN &&
            contentReviewStatus == AttachmentContentReviewStatus.PASS &&
            deletedAt == null

    private companion object {
        val VIEWABLE_DECRYPTION_STATUSES = setOf(
            AttachmentDecryptionStatus.NOT_REQUIRED,
            AttachmentDecryptionStatus.DECRYPTED
        )
    }
}

/** 附件列表与实时授权事实读取端口；实现负责隔离 Mapper 和 PO。 */
interface FeedbackAttachmentReadPort {
    /** 一次查询返回指定反馈的附件安全摘要事实，不包含路径或正文。 */
    fun findListFacts(sourceId: Long): List<AttachmentListFacts>

    /** 按反馈和附件联合键返回实时授权事实，不存在或已过保留期时返回 null。 */
    fun findAuthorizationFacts(attachmentId: Long, sourceId: Long): AttachmentAuthorizationFacts?

    /** 一次有界查询返回同一反馈下多个附件的实时授权事实，避免批量授权 N+1。 */
    fun findAuthorizationFacts(attachmentIds: Collection<Long>, sourceId: Long): List<AttachmentAuthorizationFacts>
}

/** 可见预览优先级变更；实现必须把每个 id 限定在入参 feedback 的范围内。 */
fun interface AttachmentMediaPreviewPriorityPort {
    /** 批量提升同一反馈下指定附件的预览优先级；实现必须再次限定反馈归属。 */
    fun prioritize(sourceId: Long, attachmentIds: Collection<Long>): Int
}

/** 令牌 SHA-256 指纹；构造和导出均复制字节，避免可变数组越过持久化边界。 */
class AttachmentGrantFingerprint private constructor(private val bytes: ByteArray) {
    val size: Int get() = bytes.size

    /** 返回防御性副本供数据库边界绑定参数，调用方不能修改内部指纹。 */
    fun databaseValue(): ByteArray = bytes.copyOf()

    /** 以内容比较指纹，仅用于测试或边界完整性验证。 */
    fun contentEquals(value: ByteArray): Boolean = bytes.contentEquals(value)

    companion object {
        /** 对原始一次性令牌计算 SHA-256；原始令牌不得越过当前请求。 */
        fun fromToken(token: String): AttachmentGrantFingerprint = AttachmentGrantFingerprint(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        )
    }
}

data class CreateAttachmentGrantCommand(
    val sourceId: Long,
    val attachmentId: Long,
    val userId: Long,
    val fingerprint: AttachmentGrantFingerprint,
    val expiresAt: LocalDateTime,
    /** 自由文本原因的 SHA-256/长度安全表示，不得包含调用方原文。 */
    val accessReason: String
)

/** 一次性授权持久化端口；消费锁和 CAS 必须由同一数据库事务包围。 */
interface AttachmentGrantStorePort {
    /** 插入不可逆令牌指纹；失败必须抛错以回滚授权审计事务。 */
    fun create(command: CreateAttachmentGrantCommand)

    /** 在现有短事务内锁定完整绑定且仍有效的唯一授权。 */
    fun lockConsumableId(
        fingerprint: AttachmentGrantFingerprint,
        attachmentId: Long,
        sourceId: Long,
        userId: Long,
        purpose: AttachmentGrantPurpose
    ): Long?

    /** 条件标记授权已消费；false 表示并发或状态失效。 */
    fun markConsumed(grantId: Long): Boolean
}

data class AttachmentAuthorizationAuditEvent(
    val userId: Long,
    val attachmentId: Long,
    val sourceId: Long,
    val reasonCode: AttachmentAuthorizationAction,
    val reasonSha256: String,
    val reasonLength: Int,
    val grantHashRecorded: Boolean,
    val expiresInSeconds: Long,
    val operation: AttachmentAuditOperation
)

/** 授权审计写端口；写入失败必须抛出，使授权事务整体回滚。 */
fun interface AttachmentAuthorizationAuditPort {
    /** 写入不含令牌原文和访问理由原文的授权审计，失败必须抛错。 */
    fun append(event: AttachmentAuthorizationAuditEvent)
}
