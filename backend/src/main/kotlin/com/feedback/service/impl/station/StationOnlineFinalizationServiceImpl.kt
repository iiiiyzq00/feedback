package com.feedback.service.impl.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.station.online.StationOnlineAttachmentPo
import com.feedback.model.po.station.online.StationAttachmentIdentityAdoptionPo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.repository.station.online.StationOnlineAttachmentRepository
import com.feedback.repository.station.online.StationOnlineChannelRepository
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import com.feedback.model.station.PreparedStationAttachment
import com.feedback.model.station.ResolvedStationPhone
import com.feedback.service.station.StationOnlineFinalizationService
import com.feedback.service.station.StationOnlineIdentityService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/**
 * Station Online Worker 的权威收口事务。
 *
 * 外部联系方式查询和附件下载必须在进入本事务前完成；身份、附件元数据、渠道完成状态和列表投影
 * dirty 行在同一事务中提交。任一 dirty 标记失败都会回滚正式事实，并由 Worker 保留租约等待恢复，
 * 不会被降级为普通业务重试状态。
 */
@Service
class StationOnlineFinalizationServiceImpl(
    private val channels: StationOnlineChannelRepository,
    private val identities: StationOnlineIdentityService,
    private val attachments: StationOnlineAttachmentRepository,
    private val dirtyMarker: FeedbackListProjectionDirtyMarker
) : StationOnlineFinalizationService {
    /** 批量读取已存在附件身份，下载前用于跳过重复外部 I/O。 */
    override fun existingAttachmentIdentities(sourceAttachmentIds: List<String>): Set<String> =
        if (sourceAttachmentIds.isEmpty()) emptySet()
        else attachments.findBySourceIdentities(sourceAttachmentIds).mapNotNull { it.sourceAttachmentId }.toSet()

    @Transactional
    /** 在短写事务中提交 Station 成功状态、身份和附件元数据，并按条件写入投影脏标记。 */
    override fun finalizeSuccess(
        sourceId: Long,
        leaseOwner: String,
        resolvedPhone: ResolvedStationPhone?,
        contactUnavailable: Boolean,
        preparedAttachments: List<PreparedStationAttachment>,
        unavailableAttachments: Int
    ) {
        if (preparedAttachments.size > 9 || unavailableAttachments !in 0..9) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_ATTACHMENT_BATCH_INVALID")
        }
        preparedAttachments.forEach { prepared ->
            if (prepared.sourceId != sourceId) {
                throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_ATTACHMENT_SOURCE_MISMATCH")
            }
            registerRollbackCleanup(prepared)
        }

        if (contactUnavailable) {
            channels.markContactUnavailable(sourceId, leaseOwner)
        }
        val identityInserted = resolvedPhone?.let {
            identities.storePhone(sourceId, it.value, it.authorizationBasis)
        } == true
        val attachmentChanged = persistAttachments(preparedAttachments)
        channels.complete(
            sourceId,
            leaseOwner,
            unavailableAttachments,
            identityStored = resolvedPhone != null
        )

        markOrFail(sourceId, FeedbackListProjectionDirtyReason.CHANNEL)
        if (identityInserted) {
            markOrFail(sourceId, FeedbackListProjectionDirtyReason.IDENTITY)
        }
        if (attachmentChanged) {
            markOrFail(sourceId, FeedbackListProjectionDirtyReason.ATTACHMENT)
        }
    }

    @Transactional
    /** 在短写事务中按 lease owner 推进失败或重试状态，只持久化安全错误码与受控摘要。 */
    override fun finalizeFailure(
        sourceId: Long,
        leaseOwner: String,
        status: String,
        retryCount: Int,
        delaySeconds: Long,
        review: Boolean,
        errorCode: String
    ) {
        channels.fail(
            sourceId,
            leaseOwner,
            status,
            retryCount,
            delaySeconds,
            review,
            errorCode,
            "Station online processing attempt failed"
        )
        markOrFail(sourceId, FeedbackListProjectionDirtyReason.CHANNEL)
    }

    /** 在当前收口事务中去重并写入附件元数据；按来源身份和摘要复核，回滚时清理本次拥有的文件。 */
    private fun persistAttachments(preparedAttachments: List<PreparedStationAttachment>): Boolean {
        if (preparedAttachments.isEmpty()) {
            return false
        }
        val existingByIdentity = attachments.findBySourceIdentities(
            preparedAttachments.map { it.sourceAttachmentId }
        ).associateBy { it.sourceAttachmentId
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_ATTACHMENT_IDENTITY_MISSING") }
        val uniqueByHash = linkedMapOf<String, PreparedStationAttachment>()
        preparedAttachments.forEach { prepared ->
            existingByIdentity[prepared.sourceAttachmentId]?.let { existing ->
                if (existing.storagePath != prepared.storagePath.toString()) {
                    prepared.discard()
                }
                return@forEach
            }
            val previous = uniqueByHash.putIfAbsent(hashKey(prepared.fileSha256), prepared)
            if (previous != null) {
                prepared.discard()
            }
        }
        if (uniqueByHash.isEmpty()) {
            return false
        }

        val existingByHash = attachments.findBySourceAndHashes(
            preparedAttachments.first().sourceId,
            uniqueByHash.values.map { it.fileSha256 }
        ).fold(linkedMapOf<String, StationOnlineAttachmentPo>()) { indexed, existing ->
            val hash = existing.fileSha256
                ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_ATTACHMENT_HASH_MISSING")
            indexed.putIfAbsent(hashKey(hash), existing)
            indexed
        }
        val adoptions = mutableListOf<StationAttachmentIdentityAdoptionPo>()
        val newAttachments = mutableListOf<PreparedStationAttachment>()
        uniqueByHash.forEach { (hash, prepared) ->
            val existing = existingByHash[hash]
            when {
                existing == null -> newAttachments += prepared
                existing.sourceAttachmentId == null -> {
                    adoptions += StationAttachmentIdentityAdoptionPo(existing.id, prepared.sourceAttachmentId)
                    prepared.discard()
                }
                else -> prepared.discard()
            }
        }

        val adopted = if (adoptions.isEmpty()) 0 else attachments.adoptSourceIdentities(adoptions)
        if (newAttachments.isNotEmpty()) {
            val persistedByIdentity = attachments.insertOrLocateBatch(newAttachments.map(::attachmentRow))
                .associateBy { it.sourceAttachmentId
                    ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_ATTACHMENT_IDENTITY_MISSING") }
            newAttachments.forEach { prepared ->
                val persisted = persistedByIdentity[prepared.sourceAttachmentId]
                    ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_ATTACHMENT_NOT_FOUND_AFTER_UPSERT")
                if (persisted.storagePath != prepared.storagePath.toString()) {
                    prepared.discard()
                }
            }
        }
        return adopted > 0 || newAttachments.isNotEmpty()
    }

    /** 把已下载附件转换为持久化记录；保留摘要、隔离路径和保留期，不携带原始 URL 文本。 */
    private fun attachmentRow(prepared: PreparedStationAttachment) = StationOnlineAttachmentPo(
        publicId = uuidBytes(UUID.randomUUID()),
        sourceId = prepared.sourceId,
        mimeType = prepared.mimeType,
        fileSizeBytes = prepared.fileSizeBytes,
        fileSha256 = prepared.fileSha256,
        storagePath = prepared.storagePath.toString(),
        storageKeyId = "station-quarantine-v1",
        sourceAttachmentId = prepared.sourceAttachmentId,
        retentionUntil = prepared.retentionUntil
    )

    /** 把附件摘要编码为稳定去重键；调用方不得使用可变数组引用作为 Map 键。 */
    private fun hashKey(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    /** 按前置状态条件更新 Station 链路；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun markOrFail(sourceId: Long, reason: FeedbackListProjectionDirtyReason) {
        try {
            dirtyMarker.mark(FeedbackListProjectionDirtyCommand(listOf(sourceId), reason))
        } catch (error: Exception) {
            throw ServiceException(ErrorCode.STATION_DIRTY_MARKING_FAILED, "STATION_DIRTY_MARK_FAILED", cause = error)
        }
    }

    /** 注册事务完成回调；只有事务未提交时才删除本次新建附件，提交成功后保留正式文件。 */
    private fun registerRollbackCleanup(prepared: PreparedStationAttachment) {
        if (!prepared.ownsFile) {
            return
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_FINALIZATION_TRANSACTION_REQUIRED")
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            /** 根据事务最终状态执行附件补偿清理；回调不得发起新的业务写入。 */
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    prepared.discard()
                }
            }
        })
    }

    /** 把 UUID 转换为数据库兼容字节：Station 链路；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun uuidBytes(value: UUID): ByteArray = ByteBuffer.allocate(16)
        .putLong(value.mostSignificantBits)
        .putLong(value.leastSignificantBits)
        .array()
}
