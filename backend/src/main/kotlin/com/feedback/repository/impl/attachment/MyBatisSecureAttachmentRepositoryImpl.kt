package com.feedback.repository.impl.attachment

import com.feedback.application.port.feedback.attachment.AttachmentAuthorizationFacts
import com.feedback.application.port.feedback.attachment.AttachmentListFacts
import com.feedback.common.ErrorCode
import com.feedback.common.AttachmentContentReviewStatus
import com.feedback.common.AttachmentDecryptionStatus
import com.feedback.common.AttachmentIntegrityStatus
import com.feedback.common.AttachmentLogPreviewStatus
import com.feedback.common.AttachmentMalwareScanStatus
import com.feedback.common.AttachmentPreviewStatus
import com.feedback.common.AttachmentSecurityStatus
import com.feedback.common.AttachmentSourceKind
import com.feedback.common.AttachmentType
import com.feedback.common.AttachmentUploadStatus
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.attachment.FeedbackAttachmentMapper
import com.feedback.model.po.attachment.AttachmentMediaPreviewCandidatePo
import com.feedback.model.po.attachment.AttachmentObjectCleanupCandidatePo
import com.feedback.model.po.attachment.AttachmentObjectMigrationCandidatePo
import com.feedback.model.po.attachment.FeedbackAttachmentListPo
import com.feedback.model.po.attachment.FeedbackAttachmentPo
import com.feedback.repository.attachment.AttachmentMediaPreviewLocationFacts
import com.feedback.repository.attachment.AttachmentObjectLifecycleRepository
import com.feedback.model.attachment.AttachmentObjectLifecycleStatus
import com.feedback.repository.attachment.AttachmentObjectLocationFacts
import com.feedback.model.attachment.AttachmentObjectKey
import com.feedback.repository.attachment.AttachmentOwnerFacts
import com.feedback.repository.attachment.AttachmentPreviewRepository
import com.feedback.model.attachment.AttachmentStorageBackend
import com.feedback.repository.attachment.SecureAttachmentRepository
import com.feedback.repository.attachment.StoredAttachmentContent
import com.feedback.model.attachment.StoredAttachmentObject
import org.springframework.stereotype.Repository

/**
 * MyBatis 附件安全读取仓储实现。
 *
 * 每个入口只执行一条查询并在边界内完成 PO 转换；不打开文件或对象流，不解析当前用户，
 * 未知持久化编码和缺失安全字段统一失败关闭。
 */
@Repository
class MyBatisSecureAttachmentRepositoryImpl(
    private val mapper: FeedbackAttachmentMapper,
) : SecureAttachmentRepository {
    /** 单条查询返回非日志列表事实，PO 在仓储边界转换且不向上泄漏路径。 */
    override fun findNonLogListFacts(sourceId: Long): List<AttachmentListFacts> =
        mapper.findNonLogListBySourceId(sourceId).map(::toListFacts)

    /** 单条查询返回全部附件列表事实，数据库时刻与行在同一快照读取。 */
    override fun findListFacts(sourceId: Long): List<AttachmentListFacts> =
        mapper.findListBySourceId(sourceId).map(::toListFacts)

    /** 使用数据库聚合判断是否存在活动日志，不回拉附件集合。 */
    override fun hasActiveLog(sourceId: Long): Boolean = mapper.countActiveLogsBySourceId(sourceId) > 0

    /** 按附件与反馈联合键实时读取授权事实，保留期失效时返回 null。 */
    override fun findAuthorizationFacts(attachmentId: Long, sourceId: Long): AttachmentAuthorizationFacts? =
        mapper.findRetainedByIdAndSourceId(attachmentId, sourceId)?.let(::toAuthorizationFacts)

    /** 以单条 `IN` 查询读取有界授权事实，空集合不访问数据库。 */
    override fun findAuthorizationFacts(
        attachmentIds: Collection<Long>,
        sourceId: Long
    ): List<AttachmentAuthorizationFacts> {
        if (attachmentIds.isEmpty()) {
            return emptyList()
        }
        if (attachmentIds.size > MAX_AUTHORIZATION_BATCH_SIZE) {
            throw ClientException(ErrorCode.BAD_REQUEST, "ATTACHMENT_PREVIEW_BATCH_INVALID")
        }
        return mapper.findRetainedByIdsAndSourceId(attachmentIds, sourceId).map(::toAuthorizationFacts)
    }

    /** 只返回 SQL 已校验全部安全状态的内容事实，不在仓储层打开文件或对象流。 */
    override fun findViewableContent(attachmentId: Long): StoredAttachmentContent? =
        mapper.findViewableContentById(attachmentId)?.let { row ->
            StoredAttachmentContent(
                id = row.id,
                storagePath = row.storagePath,
                mimeType = row.mimeType ?: DEFAULT_MIME_TYPE,
                attachmentType = AttachmentType.fromStorage(row.attachmentType),
                fileSizeBytes = row.fileSizeBytes,
                logPreviewCiphertext = row.logPreviewCiphertext,
                logPreviewKeyId = row.logPreviewKeyId,
                logPreviewPlaintextBytes = row.logPreviewPlaintextBytes,
                logPreviewStatus = AttachmentLogPreviewStatus.fromStorage(row.logPreviewStatus),
            )
        }

    /** 解析已发布预览对象位置，未知状态或缺失尺寸时失败关闭。 */
    override fun findMediaPreviewLocation(id: Long): AttachmentMediaPreviewLocationFacts? =
        mapper.findMediaPreviewLocation(id)?.let { row ->
            if (row.status != AttachmentObjectLifecycleStatus.AVAILABLE.code) {
                readFailure("ATTACHMENT_MEDIA_PREVIEW_STATUS_INVALID")
            }
            AttachmentMediaPreviewLocationFacts(
                AttachmentObjectKey(row.objectKey ?: readFailure("ATTACHMENT_MEDIA_PREVIEW_KEY_MISSING")),
                row.objectVersion,
                row.objectEtag,
                row.contentLength ?: readFailure("ATTACHMENT_MEDIA_PREVIEW_LENGTH_MISSING"),
                row.width ?: readFailure("ATTACHMENT_MEDIA_PREVIEW_WIDTH_MISSING"),
                row.height ?: readFailure("ATTACHMENT_MEDIA_PREVIEW_HEIGHT_MISSING"),
            )
        }

    /** 严格解析对象位置的稳定存储 code，不完整或不支持的行失败关闭。 */
    override fun findObjectStorageLocation(attachmentId: Long): AttachmentObjectLocationFacts? =
        mapper.findObjectStorageLocationById(attachmentId)?.let(::toObjectLocation)

    /** 单条查询返回附件类型存储 code，不承担授权决策。 */
    override fun findAttachmentType(attachmentId: Long, sourceId: Long): AttachmentType? =
        AttachmentType.fromStorage(mapper.findAttachmentType(attachmentId, sourceId))

    /** 将持久化行转换为授权最小事实，缺失反馈归属时失败关闭。 */
    private fun toAuthorizationFacts(row: FeedbackAttachmentPo) = AttachmentAuthorizationFacts(
        id = row.id,
        sourceId = row.sourceId ?: readFailure("ATTACHMENT_SOURCE_ID_MISSING"),
        attachmentType = AttachmentType.fromStorage(row.attachmentType),
        securityStatus = AttachmentSecurityStatus.fromStorage(row.securityStatus),
        integrityStatus = AttachmentIntegrityStatus.fromStorage(row.integrityStatus),
        decryptionStatus = AttachmentDecryptionStatus.fromStorage(row.decryptionStatus),
        malwareScanStatus = AttachmentMalwareScanStatus.fromStorage(row.malwareScanStatus),
        contentReviewStatus = AttachmentContentReviewStatus.fromStorage(row.contentReviewStatus),
        mediaPreviewStatus = AttachmentPreviewStatus.fromStorage(row.mediaPreviewStatus),
        deletedAt = row.deletedAt,
    )

    /** 将列表 PO 转为不含路径的安全事实，缺失数据库时刻时整批失败。 */
    private fun toListFacts(row: FeedbackAttachmentListPo) = AttachmentListFacts(
        id = row.id,
        sourceId = row.sourceId,
        attachmentType = AttachmentType.fromStorage(row.attachmentType),
        mimeType = row.mimeType,
        fileSizeBytes = row.fileSizeBytes,
        width = row.width,
        height = row.height,
        durationSeconds = row.durationSeconds,
        uploadStatus = AttachmentUploadStatus.fromStorage(row.uploadStatus),
        securityStatus = AttachmentSecurityStatus.fromStorage(row.securityStatus),
        integrityStatus = AttachmentIntegrityStatus.fromStorage(row.integrityStatus),
        decryptionStatus = AttachmentDecryptionStatus.fromStorage(row.decryptionStatus),
        malwareScanStatus = AttachmentMalwareScanStatus.fromStorage(row.malwareScanStatus),
        contentReviewStatus = AttachmentContentReviewStatus.fromStorage(row.contentReviewStatus),
        sourceKind = AttachmentSourceKind.fromStorage(row.sourceKind),
        retentionUntil = row.retentionUntil,
        createdAt = row.createdAt,
        deletedAt = row.deletedAt,
        storedContentPresent = row.storedContentPresent,
        mediaPreviewStatus = AttachmentPreviewStatus.fromStorage(row.mediaPreviewStatus),
        queryTime = row.queryTime ?: readFailure("ATTACHMENT_LIST_QUERY_TIME_MISSING"),
    )

    /** 将对象存储 PO 转为具名领域事实，未知 code 和无归属行均失败关闭。 */
    private fun toObjectLocation(row: FeedbackAttachmentPo): AttachmentObjectLocationFacts {
        val backend = AttachmentStorageBackend.requireStorage(row.storageBackend)
        if (backend != AttachmentStorageBackend.S3_COMPATIBLE) {
            readFailure("ATTACHMENT_STORAGE_BACKEND_UNSUPPORTED")
        }
        val sourceId = row.sourceId
        val sdkIngestId = row.sdkIngestId
        if (sourceId == null && sdkIngestId == null) {
            readFailure("ATTACHMENT_OWNER_MISSING")
        }
        if (sourceId != null && sourceId <= 0) {
            readFailure("ATTACHMENT_SOURCE_ID_INVALID")
        }
        if (sdkIngestId != null && sdkIngestId <= 0) {
            readFailure("ATTACHMENT_SDK_INGEST_ID_INVALID")
        }
        val bucket = row.objectBucket?.takeIf(String::isNotBlank)
            ?: readFailure("ATTACHMENT_OBJECT_BUCKET_MISSING")
        val rawKey = row.objectKey?.takeIf(String::isNotBlank)
            ?: readFailure("ATTACHMENT_OBJECT_KEY_MISSING")
        val objectKey = try {
            AttachmentObjectKey(rawKey)
        } catch (error: ClientException) {
            throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, error.reasonCode, cause = error)
        }
        return AttachmentObjectLocationFacts(
            id = row.id,
            owner = AttachmentOwnerFacts(sourceId, sdkIngestId),
            backend = backend,
            bucket = bucket,
            objectKey = objectKey,
            version = row.objectVersion,
            etag = row.objectEtag,
            lifecycleStatus = AttachmentObjectLifecycleStatus.requireStorage(row.objectStorageStatus),
            migratedAt = row.objectMigratedAt,
        )
    }

    private companion object {
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        const val MAX_AUTHORIZATION_BATCH_SIZE = 20
    }
}

/**
 * MyBatis 日志和媒体预览仓储实现。
 *
 * 所有写入都是单条原子 CAS，不声明事务；文件解密、转码与 S3 I/O 由 worker 在调用前后执行。
 */
@Repository
class MyBatisAttachmentPreviewRepositoryImpl(
    private val mapper: FeedbackAttachmentMapper,
) : AttachmentPreviewRepository {
    /** 以固定上限读取日志预览候选，防止 worker 一次占用过多内存。 */
    override fun findMissingLogPreviewIds(limit: Int): List<Long> =
        mapper.findMissingLogPreviewIds(limit.coerceIn(1, MAX_LOG_PREVIEW_BATCH))

    /** 单条条件 UPDATE 写入加密预览，影响行数对应 CAS 成功与否。 */
    override fun storeLogPreview(id: Long, ciphertext: ByteArray, keyId: String, plaintextBytes: Int): Boolean =
        mapper.storeLogPreview(id, ciphertext, keyId, plaintextBytes) == 1

    /** 单条条件 UPDATE 收敛日志预览失败，错误码按列宽截断。 */
    override fun markLogPreviewFailed(id: Long, errorCode: String): Boolean =
        mapper.markLogPreviewFailed(id, errorCode.take(MAX_ERROR_CODE_LENGTH)) == 1

    /** 有界读取媒体预览候选，FFmpeg 能力只作为 SQL 筛选参数。 */
    override fun findMediaPreviewCandidates(limit: Int, ffmpegEnabled: Boolean): List<AttachmentMediaPreviewCandidatePo> =
        mapper.findMediaPreviewCandidates(limit.coerceIn(1, MAX_MEDIA_PREVIEW_BATCH), ffmpegEnabled)

    /** 单条 CAS 领取租约，租期限制为 10 至 300 秒，不额外开启事务。 */
    override fun claimMediaPreview(id: Long, leaseSeconds: Int, leaseOwner: String): Boolean =
        mapper.claimMediaPreview(id, leaseSeconds.coerceIn(MIN_LEASE_SECONDS, MAX_LEASE_SECONDS), leaseOwner) == 1

    /** 单条 CAS 续租，只有匹配当前 owner 的处理中记录可成功。 */
    override fun renewMediaPreviewLease(id: Long, leaseSeconds: Int, leaseOwner: String): Boolean =
        mapper.renewMediaPreviewLease(id, leaseSeconds.coerceIn(MIN_LEASE_SECONDS, MAX_LEASE_SECONDS), leaseOwner) == 1

    /** 单条 CAS 登记已上传预览，对象上传和元数据验证不进入数据库事务。 */
    override fun storeMediaPreview(
        id: Long,
        objectKey: AttachmentObjectKey,
        stored: StoredAttachmentObject,
        contentLength: Long,
        width: Int,
        height: Int,
        leaseOwner: String,
    ): Boolean = mapper.storeMediaPreview(
        id,
        objectKey.value,
        stored.version,
        stored.etag,
        contentLength,
        width,
        height,
        leaseOwner,
    ) == 1

    /** 单条 CAS 写入失败与 SQL 内有上限退避，只允许当前租约 owner 收口。 */
    override fun markMediaPreviewFailed(id: Long, errorCode: String, leaseOwner: String): Boolean =
        mapper.markMediaPreviewFailed(id, errorCode.take(MAX_ERROR_CODE_LENGTH), leaseOwner) == 1

    /** 空集合不访问数据库；非空集合去重并限制 20 个 ID，SQL 仍按 sourceId 限定归属。 */
    override fun prioritize(sourceId: Long, attachmentIds: Collection<Long>): Int {
        if (attachmentIds.isEmpty()) {
            return 0
        }
        return mapper.prioritizeMediaPreviews(sourceId, attachmentIds.distinct().take(MAX_MEDIA_PREVIEW_BATCH))
    }

    private companion object {
        const val MAX_LOG_PREVIEW_BATCH = 10
        const val MAX_MEDIA_PREVIEW_BATCH = 20
        const val MIN_LEASE_SECONDS = 10
        const val MAX_LEASE_SECONDS = 300
        const val MAX_ERROR_CODE_LENGTH = 100
    }
}

/**
 * MyBatis 附件对象迁移与本地副本清理仓储实现。
 *
 * 查询均有明确游标或上限；写入为单条快照 CAS，不声明额外事务。网络、文件、哈希和加解密 I/O
 * 不得移入本实现。
 */
@Repository
class MyBatisAttachmentObjectLifecycleRepositoryImpl(
    private val mapper: FeedbackAttachmentMapper,
) : AttachmentObjectLifecycleRepository {
    /** 单条查询返回指定附件的迁移快照，不打开本地文件。 */
    override fun findObjectMigrationCandidate(attachmentId: Long): AttachmentObjectMigrationCandidatePo? =
        mapper.findObjectMigrationCandidate(attachmentId)

    /** 按 ID 游标查询，上限 500，非法输入在执行 SQL 前拒绝。 */
    override fun findObjectMigrationCandidates(afterId: Long, limit: Int): List<AttachmentObjectMigrationCandidatePo> {
        if (afterId < 0) {
            throw ClientException(ErrorCode.BAD_REQUEST, "ATTACHMENT_MIGRATION_CURSOR_INVALID")
        }
        requireBatchLimit(limit)
        return mapper.findObjectMigrationCandidates(afterId, limit)
    }

    /** 使用单条聚合 SQL 返回已分类源缺失总数，不回拉 ID 列表。 */
    override fun countObjectMigrationSourceUnavailable(): Long = mapper.countObjectMigrationSourceUnavailable()

    /** 单条快照 CAS 登记源缺失，不伪造已上传的对象事实。 */
    override fun markObjectMigrationSourceUnavailable(candidate: AttachmentObjectMigrationCandidatePo): Boolean =
        mapper.markObjectMigrationSourceUnavailable(
            candidate.id,
            candidate.storagePath,
            candidate.fileSizeBytes,
            candidate.fileSha256,
        ) == 1

    /** 单条查询返回指定附件的本地副本清理快照。 */
    override fun findObjectCleanupCandidate(attachmentId: Long): AttachmentObjectCleanupCandidatePo? =
        mapper.findObjectCleanupCandidate(attachmentId)

    /** 有界读取最多 500 个本地副本清理候选。 */
    override fun findObjectCleanupCandidates(limit: Int): List<AttachmentObjectCleanupCandidatePo> {
        requireBatchLimit(limit)
        return mapper.findObjectCleanupCandidates(limit)
    }

    /** 单条 CAS 在对象位置、版本和哈希仍匹配时清除本地路径。 */
    override fun clearVerifiedLocalCopy(candidate: AttachmentObjectCleanupCandidatePo): Boolean =
        mapper.clearVerifiedLocalCopy(
            candidate.id,
            candidate.storagePath,
            candidate.objectBucket,
            candidate.objectKey,
            candidate.objectVersion,
            candidate.fileSha256,
        ) == 1

    /** 单条 CAS 在原路径快照未变时登记已验证对象，对象 I/O 已在事务外完成。 */
    override fun markObjectAvailable(
        candidate: AttachmentObjectMigrationCandidatePo,
        stored: StoredAttachmentObject,
    ): Boolean = mapper.markObjectAvailable(
        attachmentId = candidate.id,
        expectedStoragePath = candidate.storagePath,
        storageBackend = stored.backend.code,
        bucket = stored.bucket,
        objectKey = stored.key.value,
        objectVersion = stored.version,
        objectEtag = stored.etag,
    ) == 1

    /** 统一验证迁移和清理批量上限，禁止无界候选查询。 */
    private fun requireBatchLimit(limit: Int) {
        if (limit !in 1..MAX_BATCH_SIZE) {
            throw ClientException(ErrorCode.BAD_REQUEST, "ATTACHMENT_MIGRATION_BATCH_SIZE_INVALID")
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 500
    }
}

/** 将持久化事实不可恢复的异常统一转换为附件服务失败关闭语义。 */
private fun readFailure(reasonCode: String): Nothing =
    throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, reasonCode)
