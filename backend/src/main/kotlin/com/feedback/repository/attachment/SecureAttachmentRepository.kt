package com.feedback.repository.attachment

import com.feedback.model.attachment.*

import com.feedback.application.port.feedback.attachment.AttachmentListFacts
import com.feedback.application.port.feedback.attachment.AttachmentMediaPreviewPriorityPort
import com.feedback.application.port.feedback.attachment.FeedbackAttachmentReadPort
import com.feedback.common.AttachmentLogPreviewStatus
import com.feedback.common.AttachmentType
import com.feedback.model.po.attachment.AttachmentMediaPreviewCandidatePo
import com.feedback.model.po.attachment.AttachmentObjectCleanupCandidatePo
import com.feedback.model.po.attachment.AttachmentObjectMigrationCandidatePo
import java.time.LocalDateTime

/**
 * 已通过安全门的附件内容定位事实。
 *
 * 仅 RepositoryImpl 可从 PO 构造本类；本地路径是对象存储迁移前的兼容字段，
 * 不得进入公开 HTTP 响应或被视为永久 URL。
 */
data class StoredAttachmentContent(
    val id: Long,
    val storagePath: String?,
    val mimeType: String,
    val attachmentType: AttachmentType?,
    val fileSizeBytes: Long?,
    val logPreviewCiphertext: ByteArray? = null,
    val logPreviewKeyId: String? = null,
    val logPreviewPlaintextBytes: Int? = null,
    val logPreviewStatus: AttachmentLogPreviewStatus? = null,
)

/**
 * 附件单表记录的具名所有者事实。
 *
 * SDK 附件可以只有 [sdkIngestId]；完成物化后也可能同时具备两个标识。
 * 两者皆空或非正数时无法安全追溯归属，RepositoryImpl 必须失败关闭。
 */
data class AttachmentOwnerFacts(
    val sourceId: Long?,
    val sdkIngestId: Long?,
)

/** 私有对象存储中的具名位置与生命周期事实；刻意不包含本地路径或永久公开 URL。 */
data class AttachmentObjectLocationFacts(
    val id: Long,
    val owner: AttachmentOwnerFacts,
    val backend: AttachmentStorageBackend,
    val bucket: String,
    val objectKey: AttachmentObjectKey,
    val version: String?,
    val etag: String?,
    val lifecycleStatus: AttachmentObjectLifecycleStatus,
    val migratedAt: LocalDateTime?,
)

/** 私有派生预览的位置事实；不得进入公开列表响应。 */
data class AttachmentMediaPreviewLocationFacts(
    val objectKey: AttachmentObjectKey,
    val objectVersion: String?,
    val objectEtag: String?,
    val contentLength: Long,
    val width: Int,
    val height: Int,
)

/**
 * 附件安全读取仓储。
 *
 * 查询只返回单表事实，不解析当前用户、不扩大数据范围，也不把列表快照
 * 当作真实内容访问授权。每个方法均是单条有界 SQL，不额外开启事务。
 */
interface SecureAttachmentRepository : FeedbackAttachmentReadPort {
    /** 返回指定反馈的非日志附件列表事实，不包含存储位置。 */
    fun findNonLogListFacts(sourceId: Long): List<AttachmentListFacts>

    /** 只判断指定反馈是否存在未删除日志，不读取日志内容。 */
    fun hasActiveLog(sourceId: Long): Boolean

    /** 读取已通过全部安全状态门的内容定位事实；不满足时返回 null。 */
    fun findViewableContent(attachmentId: Long): StoredAttachmentContent?

    /** 读取已发布派生预览的私有对象位置；字段不完整时失败关闭。 */
    fun findMediaPreviewLocation(id: Long): AttachmentMediaPreviewLocationFacts?

    /** 读取附件对象存储位置与生命周期；未知存储编码失败关闭。 */
    fun findObjectStorageLocation(attachmentId: Long): AttachmentObjectLocationFacts?

    /** 返回归属于指定反馈的附件类型存储编码；不存在时返回 null。 */
    fun findAttachmentType(attachmentId: Long, sourceId: Long): AttachmentType?
}

/**
 * 日志和媒体派生预览仓储。
 *
 * 领取、续租、完成与失败均为单条条件 UPDATE/CAS，无前置数据库判断，
 * 不额外加事务。生成、转码和对象上传必须在调用这些 CAS 之外完成。
 */
interface AttachmentPreviewRepository : AttachmentMediaPreviewPriorityPort {
    /** 有界读取缺失加密日志预览的附件 ID。 */
    fun findMissingLogPreviewIds(limit: Int): List<Long>

    /** 条件写入加密日志预览；已被其他 worker 处理时返回 false。 */
    fun storeLogPreview(id: Long, ciphertext: ByteArray, keyId: String, plaintextBytes: Int): Boolean

    /** 条件标记日志预览失败；错误码按存储列宽截断。 */
    fun markLogPreviewFailed(id: Long, errorCode: String): Boolean

    /** 有界读取到期或可重试的媒体预览任务。 */
    fun findMediaPreviewCandidates(limit: Int, ffmpegEnabled: Boolean): List<AttachmentMediaPreviewCandidatePo>

    /** 用所有者和租期条件原子领取媒体预览任务。 */
    fun claimMediaPreview(id: Long, leaseSeconds: Int, leaseOwner: String): Boolean

    /** 仅当前所有者可续租，用于转码超时前的有界心跳。 */
    fun renewMediaPreviewLease(id: Long, leaseSeconds: Int, leaseOwner: String): Boolean

    /** 仅当前租约所有者可登记已验证预览对象。 */
    fun storeMediaPreview(
        id: Long,
        objectKey: AttachmentObjectKey,
        stored: StoredAttachmentObject,
        contentLength: Long,
        width: Int,
        height: Int,
        leaseOwner: String,
    ): Boolean

    /** 仅当前租约所有者可写失败及有上限退避状态。 */
    fun markMediaPreviewFailed(id: Long, errorCode: String, leaseOwner: String): Boolean
}

/**
 * 附件对象迁移与本地副本清理仓储。
 *
 * 候选查询必须分页有界；所有变更都是带旧路径、哈希或对象版本快照的单条 CAS。
 * S3、文件系统与哈希计算必须在仓储方法之外执行。
 */
interface AttachmentObjectLifecycleRepository {
    /** 读取单个指定附件的迁移候选快照。 */
    fun findObjectMigrationCandidate(attachmentId: Long): AttachmentObjectMigrationCandidatePo?

    /** 按 ID 游标有界读取迁移候选；非法边界输入直接拒绝。 */
    fun findObjectMigrationCandidates(afterId: Long, limit: Int): List<AttachmentObjectMigrationCandidatePo>

    /** 统计已明确分类为本机源缺失的迁移终态。 */
    fun countObjectMigrationSourceUnavailable(): Long

    /** 以候选快照 CAS 登记本机源缺失，不伪造对象位置。 */
    fun markObjectMigrationSourceUnavailable(candidate: AttachmentObjectMigrationCandidatePo): Boolean

    /** 读取单个附件的本地副本清理快照。 */
    fun findObjectCleanupCandidate(attachmentId: Long): AttachmentObjectCleanupCandidatePo?

    /** 有界读取可清理的本地副本快照。 */
    fun findObjectCleanupCandidates(limit: Int): List<AttachmentObjectCleanupCandidatePo>

    /** 仅在对象和本地快照仍一致时 CAS 清除本地路径。 */
    fun clearVerifiedLocalCopy(candidate: AttachmentObjectCleanupCandidatePo): Boolean

    /** 在事务外上传并验证对象后，以原候选快照 CAS 登记 AVAILABLE。 */
    fun markObjectAvailable(candidate: AttachmentObjectMigrationCandidatePo, stored: StoredAttachmentObject): Boolean
}
