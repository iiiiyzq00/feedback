package com.feedback.mapper.attachment

import com.feedback.model.po.attachment.AttachmentObjectCleanupCandidatePo
import com.feedback.model.po.attachment.AttachmentObjectMigrationCandidatePo
import com.feedback.model.po.attachment.AttachmentScanCandidatePo
import com.feedback.model.po.attachment.AttachmentMediaPreviewCandidatePo
import com.feedback.model.po.attachment.AttachmentMediaPreviewLocationPo
import com.feedback.model.po.attachment.FeedbackAttachmentListPo
import com.feedback.model.po.attachment.FeedbackAttachmentPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * `t_feedback_attachment` 的单表持久化边界。
 *
 * Mapper 只返回权威行事实，不判断当前用户的数据范围、字段权限或内容访问权。
 */
@Mapper
interface FeedbackAttachmentMapper {
    /** 插入附件记录并由 MyBatis 回填主键。 */
    fun insert(row: FeedbackAttachmentPo): Int

    /** 按主键更新附件落盘与安全状态字段。 */
    fun updateStored(row: FeedbackAttachmentPo): Int

    /** 按主键读取附件写模型。 */
    fun findById(@Param("id") id: Long): FeedbackAttachmentPo?

    /** 按来源种类与来源附件标识读取幂等记录。 */
    fun findBySourceKindAndSourceAttachmentId(
        @Param("sourceKind") sourceKind: String,
        @Param("sourceAttachmentId") sourceAttachmentId: String
    ): FeedbackAttachmentPo?

    /** 按反馈、哈希与来源种类读取去重记录。 */
    fun findBySourceHashKind(
        @Param("sourceId") sourceId: Long,
        @Param("fileSha256") fileSha256: ByteArray,
        @Param("sourceKind") sourceKind: String
    ): FeedbackAttachmentPo?

    /** 将 SDK 暂存附件关联到反馈。 */
    fun linkToSource(@Param("sdkIngestId") sdkIngestId: Long, @Param("sourceId") sourceId: Long): Int

    /** 按反馈 ID 读取全部非日志附件及同一语句的数据库时刻。 */
    fun findNonLogListBySourceId(@Param("sourceId") sourceId: Long): List<FeedbackAttachmentListPo>

    /** 列表证据区读取全部附件摘要；日志同样只返回安全元数据，不返回正文或存储位置。 */
    fun findListBySourceId(@Param("sourceId") sourceId: Long): List<FeedbackAttachmentListPo>

    /** 聚合统计指定反馈的未删除日志附件数。 */
    fun countActiveLogsBySourceId(@Param("sourceId") sourceId: Long): Long

    /** 按附件与反馈联合键读取仍在保留期内的实时授权事实。 */
    fun findRetainedByIdAndSourceId(
        @Param("attachmentId") attachmentId: Long,
        @Param("sourceId") sourceId: Long
    ): FeedbackAttachmentPo?

    /** 按同一反馈与有界附件 ID 集合批量读取仍在保留期内的授权事实。 */
    fun findRetainedByIdsAndSourceId(
        @Param("attachmentIds") attachmentIds: Collection<Long>,
        @Param("sourceId") sourceId: Long
    ): List<FeedbackAttachmentPo>

    /** 只读取满足全部安全门禁的内容定位字段。 */
    fun findViewableContentById(@Param("attachmentId") attachmentId: Long): FeedbackAttachmentPo?

    /** 返回对象存储位置与生命周期事实，不返回公开 URL；读取器据 AVAILABLE 状态选择唯一权威来源。 */
    fun findObjectStorageLocationById(@Param("attachmentId") attachmentId: Long): FeedbackAttachmentPo?

    /** 读取指定附件的对象迁移候选快照。 */
    fun findObjectMigrationCandidate(@Param("attachmentId") attachmentId: Long): AttachmentObjectMigrationCandidatePo?

    /** 按 ID 游标和上限读取对象迁移候选。 */
    fun findObjectMigrationCandidates(
        @Param("afterId") afterId: Long,
        @Param("limit") limit: Int
    ): List<AttachmentObjectMigrationCandidatePo>

    /** 统计已收敛为本机源缺失的迁移记录。 */
    fun countObjectMigrationSourceUnavailable(): Long

    /** 以本地路径快照为条件将已验证对象原子登记为可用。 */
    fun markObjectAvailable(
        @Param("attachmentId") attachmentId: Long,
        @Param("expectedStoragePath") expectedStoragePath: String,
        @Param("storageBackend") storageBackend: String,
        @Param("bucket") bucket: String,
        @Param("objectKey") objectKey: String,
        @Param("objectVersion") objectVersion: String?,
        @Param("objectEtag") objectEtag: String?
    ): Int

    /** 以单行 CAS 记录白名单根内已缺失的本机源，供迁移候选明确失败收敛。 */
    fun markObjectMigrationSourceUnavailable(
        @Param("attachmentId") attachmentId: Long,
        @Param("expectedStoragePath") expectedStoragePath: String,
        @Param("fileSizeBytes") fileSizeBytes: Long,
        @Param("fileSha256") fileSha256: ByteArray
    ): Int

    /** 读取指定附件的本地副本清理快照。 */
    fun findObjectCleanupCandidate(@Param("attachmentId") attachmentId: Long): AttachmentObjectCleanupCandidatePo?

    /** 按固定上限读取本地副本清理候选。 */
    fun findObjectCleanupCandidates(@Param("limit") limit: Int): List<AttachmentObjectCleanupCandidatePo>

    /** 在对象与本地快照仍一致时原子清除本地路径。 */
    fun clearVerifiedLocalCopy(
        @Param("attachmentId") attachmentId: Long,
        @Param("expectedStoragePath") expectedStoragePath: String,
        @Param("bucket") bucket: String,
        @Param("objectKey") objectKey: String,
        @Param("objectVersion") objectVersion: String?,
        @Param("fileSha256") fileSha256: ByteArray
    ): Int
    /** 按附件与反馈联合键读取稳定附件类型编码。 */
    fun findAttachmentType(
        @Param("attachmentId") attachmentId: Long,
        @Param("sourceId") sourceId: Long
    ): String?

    /** 孤儿清理只读取候选集合中已登记的存储路径，避免逐文件查询。 */
    fun findRegisteredStoragePaths(@Param("storagePaths") storagePaths: Collection<String>): List<String>

    /** 按上限读取到期的隔离区扫描候选。 */
    fun findPendingSecurityScan(@Param("limit") limit: Int): List<AttachmentScanCandidatePo>

    /** 条件登记安全验证结果、密文路径及可选日志预览。 */
    fun markSecurityVerified(
        @Param("id") id: Long,
        @Param("path") path: String,
        @Param("keyId") keyId: String,
        @Param("logPreviewCiphertext") logPreviewCiphertext: ByteArray?,
        @Param("logPreviewPlaintextBytes") logPreviewPlaintextBytes: Int?
    ): Int

    /** 按上限读取缺失日志预览的已验证附件 ID。 */
    fun findMissingLogPreviewIds(@Param("limit") limit: Int): List<Long>

    /** 条件写入有界日志预览密文。 */
    fun storeLogPreview(
        @Param("id") id: Long,
        @Param("ciphertext") ciphertext: ByteArray,
        @Param("keyId") keyId: String,
        @Param("plaintextBytes") plaintextBytes: Int
    ): Int

    /** 条件登记日志预览失败码。 */
    fun markLogPreviewFailed(@Param("id") id: Long, @Param("errorCode") errorCode: String): Int

    /** 按能力与上限读取媒体预览候选。 */
    fun findMediaPreviewCandidates(
        @Param("limit") limit: Int,
        @Param("ffmpegEnabled") ffmpegEnabled: Boolean
    ): List<AttachmentMediaPreviewCandidatePo>

    /** 以条件更新原子领取媒体预览租约。 */
    fun claimMediaPreview(
        @Param("id") id: Long,
        @Param("leaseSeconds") leaseSeconds: Int,
        @Param("leaseOwner") leaseOwner: String
    ): Int

    /** 仅由当前租约所有者续租媒体预览任务。 */
    fun renewMediaPreviewLease(
        @Param("id") id: Long,
        @Param("leaseSeconds") leaseSeconds: Int,
        @Param("leaseOwner") leaseOwner: String
    ): Int

    /** 仅由当前租约所有者登记已校验的媒体预览对象。 */
    fun storeMediaPreview(
        @Param("id") id: Long,
        @Param("objectKey") objectKey: String,
        @Param("objectVersion") objectVersion: String?,
        @Param("objectEtag") objectEtag: String?,
        @Param("contentLength") contentLength: Long,
        @Param("width") width: Int,
        @Param("height") height: Int,
        @Param("leaseOwner") leaseOwner: String
    ): Int

    /** 仅由当前租约所有者登记有上限退避的失败状态。 */
    fun markMediaPreviewFailed(
        @Param("id") id: Long,
        @Param("errorCode") errorCode: String,
        @Param("leaseOwner") leaseOwner: String
    ): Int

    /** 批量提升同一反馈下可见媒体预览的处理优先级。 */
    fun prioritizeMediaPreviews(
        @Param("sourceId") sourceId: Long,
        @Param("attachmentIds") attachmentIds: Collection<Long>
    ): Int

    /** 读取已发布媒体预览的对象位置与尺寸。 */
    fun findMediaPreviewLocation(@Param("id") id: Long): AttachmentMediaPreviewLocationPo?

    /** 条件登记内容类型暂不支持。 */
    fun markContentUnsupported(@Param("id") id: Long): Int

    /** 条件登记内容审核拒绝。 */
    fun markContentRejected(@Param("id") id: Long): Int

    /** 条件登记恶意软件感染。 */
    fun markMalwareInfected(@Param("id") id: Long): Int

    /** 条件登记扫描器不可用与下一次重试时间。 */
    fun markScanUnavailable(@Param("id") id: Long, @Param("code") code: String): Int
}
