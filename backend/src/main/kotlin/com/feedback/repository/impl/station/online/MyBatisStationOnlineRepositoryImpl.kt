package com.feedback.repository.impl.station.online

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.station.online.*
import com.feedback.model.po.station.online.*
import com.feedback.repository.station.online.*
import org.springframework.stereotype.Repository

private const val MAX_SOURCE_BATCH = 200

/** Station Online 来源仓储实现；校验幂等 upsert 结果、主键回填和分页边界。 */
@Repository
class MyBatisStationOnlineSourceRepositoryImpl(private val mapper: StationOnlineSourceMapper) : StationOnlineSourceRepository {
    /** 插入并在唯一键竞争后定位权威Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insertOrLocate(row: StationOnlineSourcePo): Int {
        val affected = mapper.insertOrLocate(row)
        requireUpsertAffectedRows(affected, "STATION_SOURCE_UPSERT_FAILED")
        requireService(row.id > 0, "STATION_SOURCE_ID_MISSING")
        return affected
    }

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun get(sourceId: Long): StationOnlineSourcePo = mapper.findById(positiveId(sourceId))
        ?: throw ClientException(ErrorCode.NOT_FOUND, "STATION_SOURCE_NOT_FOUND")

    /** 按游标和显式上限分页查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findPageAfterId(afterSourceId: Long, limit: Int): List<StationOnlineSourcePo> {
        requireClient(afterSourceId >= 0 && limit in 1..MAX_SOURCE_BATCH, "STATION_SOURCE_PAGE_INVALID")
        return mapper.findPageAfterId(afterSourceId, limit)
    }

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findByIds(sourceIds: List<Long>): List<StationOnlineSourcePo> = mapper.findByIds(boundedIds(sourceIds))
}

/** Station Online 正文仓储实现；隔离正文写入、批量读取和脱敏恢复条件更新。 */
@Repository
class MyBatisStationOnlineContentRepositoryImpl(private val mapper: StationOnlineContentMapper) : StationOnlineContentRepository {
    /** 插入已校验的Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insert(row: StationOnlineContentPo) {
        requireService(mapper.insert(row) == 1, "STATION_CONTENT_INSERT_FAILED")
    }

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findBySourceIds(sourceIds: List<Long>): List<StationOnlineContentPo> =
        mapper.findBySourceIds(boundedIds(sourceIds))

    /** 按前置状态条件更新 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun markMaskingPass(row: StationOnlineContentPo) {
        requireService(mapper.markMaskingPass(row) == 1, "STATION_MASKING_CONTENT_UPDATE_FAILED")
    }

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findMaskingReviewSourceIdsAfterId(afterSourceId: Long, limit: Int): List<Long> {
        requireClient(afterSourceId >= 0 && limit in 1..MAX_SOURCE_BATCH, "STATION_MASKING_PAGE_INVALID")
        return mapper.findMaskingReviewSourceIdsAfterId(afterSourceId, limit)
    }
}

/** Station Online 渠道仓储实现；集中校验运维分页、租约 owner、重试和状态推进。 */
@Repository
class MyBatisStationOnlineChannelRepositoryImpl(private val mapper: StationOnlineChannelMapper) : StationOnlineChannelRepository {
    /** 插入已校验的Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insert(row: StationOnlineChannelPo) {
        requireService(mapper.insert(row) == 1, "STATION_CHANNEL_INSERT_FAILED")
    }

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun find(sourceId: Long): StationOnlineChannelPo? = mapper.findBySourceId(positiveId(sourceId))
    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun get(sourceId: Long): StationOnlineChannelPo = find(sourceId)
        ?: throw ClientException(ErrorCode.NOT_FOUND, "STATION_CHANNEL_NOT_FOUND")

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findBySourceIds(sourceIds: List<Long>): List<StationOnlineChannelPo> =
        mapper.findBySourceIds(boundedIds(sourceIds))

    /** 查询 Station 运维视图：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun operations(status: String?, page: Int, size: Int): Pair<List<StationOperationsTaskPo>, Long> {
        requireClient(page >= 1 && size in 1..100, "STATION_OPERATIONS_PAGE_INVALID")
        val offset = (page.toLong() - 1L) * size
        return mapper.findOperationsPage(status, offset, size) to mapper.countOperations(status)
    }

    /** 查询 Station 运维视图：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun operationsSummary(): Map<String, Long> = mapper.summarizeOperations().associate { it.status to it.count }

    /** 锁定符合条件的可重试Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findRetryableByReceiptsForUpdate(receiptIds: List<ByteArray>): List<StationOnlineChannelPo> {
        requireClient(receiptIds.isNotEmpty() && receiptIds.size <= 100 && receiptIds.all { it.size == 16 },
            "STATION_RETRY_RECEIPTS_INVALID")
        return mapper.findRetryableByReceiptsForUpdate(receiptIds)
    }

    /** 按稳定顺序锁定并领取一个可处理任务；租约 owner 是后续续租和状态推进的必要条件。 */
    override fun claimOne(leaseOwner: String): StationOnlineChannelPo? {
        requireLeaseOwner(leaseOwner)
        val row = mapper.findClaimableForUpdate() ?: return null
        requireService(mapper.claim(row.sourceId, leaseOwner) == 1, "STATION_CHANNEL_CLAIM_FAILED")
        return row.copy(leaseOwner = leaseOwner, ingestStatus = "PROCESSING", processingStage = "CONTACT")
    }

    /** 以当前版本和租约条件领取记录；竞争失败返回未领取，不覆盖其他 Worker 的租约。 */
    override fun claim(sourceId: Long, leaseOwner: String): Boolean {
        requireLeaseOwner(leaseOwner)
        val affected = mapper.claim(positiveId(sourceId), leaseOwner)
        requireService(affected == 0 || affected == 1, "STATION_CHANNEL_CLAIM_FAILED")
        return affected == 1
    }

    /** 仅允许当前 lease owner 延长未过期租约；影响行数异常按租约丢失处理。 */
    override fun renewLease(sourceId: Long, leaseOwner: String) {
        requireLeaseOwner(leaseOwner)
        requireService(mapper.renewLease(positiveId(sourceId), leaseOwner) == 1, "STATION_CHANNEL_LEASE_LOST")
    }

    /** 按前置状态条件更新 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun markContactUnavailable(sourceId: Long, leaseOwner: String) {
        requireService(mapper.markContactUnavailable(positiveId(sourceId), leaseOwner) == 1, "STATION_CHANNEL_CONTACT_UPDATE_FAILED")
    }

    /** 按当前状态或租约条件完成Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun complete(sourceId: Long, leaseOwner: String, unavailableAttachments: Int, identityStored: Boolean) {
        requireClient(unavailableAttachments in 0..9, "STATION_ATTACHMENT_UNAVAILABLE_COUNT_INVALID")
        requireService(mapper.complete(positiveId(sourceId), leaseOwner, unavailableAttachments, identityStored) == 1,
            "STATION_CHANNEL_COMPLETE_FAILED")
    }

    /** 按当前状态或租约条件记录失败的 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun fail(sourceId: Long, leaseOwner: String, status: String, retryCount: Int, delaySeconds: Long,
             review: Boolean, errorCode: String, errorSummary: String) {
        requireClient(status in setOf("REVIEW_NEEDED", "RETRY_PENDING", "FAILED_FINAL"), "STATION_FAILURE_STATUS_INVALID")
        requireClient(retryCount in 1..100 && delaySeconds in 1..1800, "STATION_FAILURE_RETRY_INVALID")
        requireSafeError(errorCode, 50)
        requireSafeError(errorSummary, 500)
        requireService(mapper.fail(sourceId, leaseOwner, status, retryCount, delaySeconds, review, errorCode, errorSummary) == 1,
            "STATION_CHANNEL_FAILURE_UPDATE_FAILED")
    }

    /** 按条件重置可重试Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun resetRetryable(sourceIds: List<Long>) {
        val ids = boundedIds(sourceIds, 100)
        requireService(mapper.resetRetryable(ids) == ids.size, "STATION_RETRY_STATE_UPDATE_FAILED")
    }

    /** 按前置状态条件更新 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun markMaskingRecovered(sourceId: Long) {
        requireService(mapper.markMaskingRecovered(positiveId(sourceId)) == 1, "STATION_MASKING_CHANNEL_UPDATE_FAILED")
    }

    /** 按前置状态条件更新 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun markMaskingFailure(sourceId: Long, errorSummary: String) {
        requireSafeError(errorSummary, 500)
        requireService(mapper.markMaskingFailure(positiveId(sourceId), errorSummary) == 1, "STATION_MASKING_FAILURE_UPDATE_FAILED")
    }
}

/** Station Online 原始载荷仓储实现；按来源幂等保存密文并限制批量读取。 */
@Repository
class MyBatisStationOnlineRawPayloadRepositoryImpl(private val mapper: StationOnlineRawPayloadMapper) : StationOnlineRawPayloadRepository {
    /** 按唯一业务键幂等写入 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun upsert(row: StationOnlineRawPayloadPo): Int = mapper.upsert(row).also {
        requireUpsertAffectedRows(it, "STATION_RAW_UPSERT_FAILED")
    }

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findBySourceIds(sourceIds: List<Long>): List<StationOnlineRawPayloadPo> =
        mapper.findBySourceIds(boundedIds(sourceIds))
}

/** Station Online 身份仓储实现；依靠唯一约束执行首次写入并提供有界读取。 */
@Repository
class MyBatisStationOnlineIdentityRepositoryImpl(private val mapper: StationOnlineIdentityMapper) : StationOnlineIdentityRepository {
    /** 以唯一约束执行先写者获胜的Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insertFirstWins(row: StationOnlineIdentityPo): Boolean {
        val affected = mapper.insertIgnore(row)
        requireService(affected == 0 || affected == 1, "STATION_IDENTITY_INSERT_FAILED")
        return affected == 1
    }
}

/** Station Online 附件仓储实现；按来源身份和摘要去重，批量操作最多九条。 */
@Repository
class MyBatisStationOnlineAttachmentRepositoryImpl(private val mapper: StationOnlineAttachmentMapper) : StationOnlineAttachmentRepository {
    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findBySourceIdentity(sourceAttachmentId: String): StationOnlineAttachmentPo? {
        requireClient(sourceAttachmentId.isNotBlank() && sourceAttachmentId.length <= 100, "STATION_ATTACHMENT_IDENTITY_INVALID")
        return mapper.findBySourceIdentity(sourceAttachmentId)
    }

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findBySourceAndHash(sourceId: Long, hash: ByteArray): StationOnlineAttachmentPo? {
        requireClient(hash.size == 32, "STATION_ATTACHMENT_HASH_INVALID")
        return mapper.findBySourceAndHash(positiveId(sourceId), hash)
    }

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findBySourceIdentities(sourceAttachmentIds: List<String>): List<StationOnlineAttachmentPo> {
        val identities = boundedAttachmentIdentities(sourceAttachmentIds)
        return mapper.findBySourceIdentities(identities)
    }

    /** 按受控条件查询 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findBySourceAndHashes(sourceId: Long, hashes: List<ByteArray>): List<StationOnlineAttachmentPo> {
        requireClient(hashes.isNotEmpty() && hashes.size <= 9 && hashes.all { it.size == 32 },
            "STATION_ATTACHMENT_HASH_BATCH_INVALID")
        return mapper.findBySourceAndHashes(positiveId(sourceId), hashes.fold(mutableListOf<ByteArray>()) { unique, hash ->
            if (unique.none(hash::contentEquals)) {
                unique += hash
            }
            unique
        })
    }

    /** 插入并在唯一键竞争后定位权威Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insertOrLocate(row: StationOnlineAttachmentPo): StationOnlineAttachmentPo {
        val sourceAttachmentId = row.sourceAttachmentId?.takeIf { it.isNotBlank() && it.length <= 100 }
            ?: throw ClientException(ErrorCode.BAD_REQUEST, "STATION_ATTACHMENT_IDENTITY_INVALID")
        requireUpsertAffectedRows(mapper.insertOrLocate(row), "STATION_ATTACHMENT_UPSERT_FAILED")
        return mapper.findBySourceIdentity(sourceAttachmentId)
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_ATTACHMENT_NOT_FOUND_AFTER_UPSERT")
    }

    /** 以条件更新认领Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun adoptSourceIdentity(id: Long, sourceAttachmentId: String): Boolean {
        requireClient(sourceAttachmentId.isNotBlank() && sourceAttachmentId.length <= 100,
            "STATION_ATTACHMENT_IDENTITY_INVALID")
        val affected = mapper.adoptSourceIdentity(positiveId(id), sourceAttachmentId)
        requireService(affected == 0 || affected == 1, "STATION_ATTACHMENT_IDENTITY_UPDATE_FAILED")
        return affected == 1
    }

    /** 批量插入或定位唯一键对应的Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insertOrLocateBatch(rows: List<StationOnlineAttachmentPo>): List<StationOnlineAttachmentPo> {
        requireClient(rows.isNotEmpty() && rows.size <= 9, "STATION_ATTACHMENT_BATCH_INVALID")
        val identities = boundedAttachmentIdentities(rows.map { it.sourceAttachmentId.orEmpty() })
        requireClient(identities.size == rows.size, "STATION_ATTACHMENT_BATCH_DUPLICATE_IDENTITY")
        val affected = mapper.insertOrLocateBatch(rows)
        requireService(affected in 0..(rows.size * 2), "STATION_ATTACHMENT_BATCH_UPSERT_FAILED")
        return mapper.findBySourceIdentities(identities).also {
            requireService(it.size == identities.size, "STATION_ATTACHMENT_BATCH_NOT_FOUND_AFTER_UPSERT")
        }
    }

    /** 以条件更新认领Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun adoptSourceIdentities(rows: List<StationAttachmentIdentityAdoptionPo>): Int {
        requireClient(rows.isNotEmpty() && rows.size <= 9 && rows.map { it.id }.distinct().size == rows.size,
            "STATION_ATTACHMENT_ADOPTION_BATCH_INVALID")
        boundedAttachmentIdentities(rows.map { it.sourceAttachmentId })
        return mapper.adoptSourceIdentities(rows).also {
            requireService(it in 0..rows.size, "STATION_ATTACHMENT_IDENTITY_UPDATE_FAILED")
        }
    }
}

/** Station 双写任务仓储实现；维护命令指纹、租约领取及 owner 条件状态更新。 */
@Repository
class MyBatisStationDualWriteTaskRepositoryImpl(private val mapper: StationDualWriteTaskMapper) : StationDualWriteTaskRepository {
    /** 插入并在唯一键竞争后定位权威Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insertOrLocate(row: StationDualWriteTaskPo): StationDualWriteTaskPo {
        requireUpsertAffectedRows(mapper.insertOrLocate(row), "STATION_DUAL_TASK_UPSERT_FAILED")
        return mapper.findByIdempotencyKey(row.idempotencyKey)
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_DUAL_TASK_NOT_FOUND_AFTER_UPSERT")
    }

    /** 按稳定顺序锁定并领取一个可处理任务；租约 owner 是后续续租和状态推进的必要条件。 */
    override fun claimOne(leaseOwner: String): StationDualWriteTaskPo? {
        requireLeaseOwner(leaseOwner)
        val row = mapper.findClaimableForUpdate() ?: return null
        requireService(mapper.claim(row.id, leaseOwner) == 1, "STATION_DUAL_TASK_CLAIM_FAILED")
        return mapper.findClaimed(row.id, leaseOwner)
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_DUAL_TASK_CLAIM_LOST")
    }

    /** 按前置状态条件更新 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun markOldSucceeded(id: Long, leaseOwner: String) {
        requireService(mapper.markOldSucceeded(positiveId(id), leaseOwner) == 1, "STATION_DUAL_OLD_STATE_UPDATE_FAILED")
    }

    /** 按当前状态或租约条件完成Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun markCompleted(id: Long, leaseOwner: String, sourceId: Long) {
        requireService(mapper.markCompleted(positiveId(id), leaseOwner, positiveId(sourceId)) == 1,
            "STATION_DUAL_COMPLETE_STATE_UPDATE_FAILED")
    }

    /** 按当前状态或租约条件记录失败的 Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun markFailure(id: Long, leaseOwner: String, overallStatus: String, retryCount: Int, failedStatus: String,
                    finalFailure: Boolean, delaySeconds: Long, errorCode: String, errorSummary: String) {
        requireClient(overallStatus in setOf("RETRY_PENDING", "FAILED_FINAL"), "STATION_DUAL_FAILURE_STATUS_INVALID")
        requireClient(failedStatus in setOf("RETRY_PENDING", "FAILED_FINAL"), "STATION_DUAL_BRANCH_STATUS_INVALID")
        requireClient(retryCount in 1..100 && delaySeconds in 1..1800, "STATION_DUAL_RETRY_INVALID")
        requireSafeError(errorCode, 50)
        requireSafeError(errorSummary, 500)
        requireService(mapper.markFailure(id, leaseOwner, overallStatus, retryCount, failedStatus, finalFailure,
            delaySeconds, errorCode, errorSummary) == 1, "STATION_DUAL_FAILURE_UPDATE_FAILED")
    }
}

/** 校验输入或不变量：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
private fun requireUpsertAffectedRows(value: Int, code: String) {
    requireService(value in 0..2, code)
}

/** 校验正数数据库标识：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
private fun positiveId(value: Long): Long = value.also { requireClient(it > 0, "STATION_SOURCE_ID_INVALID") }

/** 校验集合边界：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
private fun boundedIds(values: List<Long>, max: Int = MAX_SOURCE_BATCH): List<Long> = values.distinct().sorted().also {
    requireClient(it.isNotEmpty() && it.size <= max && it.all { id -> id > 0 }, "STATION_SOURCE_IDS_INVALID")
}

/** 校验输入或不变量：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
private fun requireLeaseOwner(value: String) {
    requireClient(value.isNotBlank() && value.length <= 100 && value.none(Char::isISOControl), "STATION_LEASE_OWNER_INVALID")
}

/** 校验输入或不变量：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
private fun requireSafeError(value: String, maxLength: Int) {
    requireClient(value.isNotBlank() && value.length <= maxLength && value.none(Char::isISOControl), "STATION_ERROR_DETAIL_INVALID")
}

/** 校验集合边界：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
private fun boundedAttachmentIdentities(values: List<String>): List<String> = values.distinct().also {
    requireClient(it.isNotEmpty() && it.size <= 9 && it.all { value -> value.isNotBlank() && value.length <= 100 },
        "STATION_ATTACHMENT_IDENTITY_BATCH_INVALID")
}

/** 校验输入或不变量：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
private fun requireClient(condition: Boolean, reasonCode: String) {
    if (!condition) {
        throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)
    }
}

/** 校验输入或不变量：Station 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
private fun requireService(condition: Boolean, reasonCode: String) {
    if (!condition) {
        throw ServiceException(ErrorCode.INTERNAL_ERROR, reasonCode)
    }
}
