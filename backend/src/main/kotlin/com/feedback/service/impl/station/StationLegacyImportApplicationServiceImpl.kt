package com.feedback.service.impl.station

import com.feedback.model.station.StationLegacyPhoneDecision
import com.feedback.model.station.StationLegacyPreparedRow
import com.feedback.convert.station.StationLegacyRowAssembler
import com.feedback.model.station.StationLegacyExportRow
import com.feedback.model.station.StationLegacyRecoveryRow
import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.station.legacy.*
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.repository.station.legacy.StationLegacyRepository
import com.feedback.service.normalization.FaultOccurrenceTimeResolver
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import com.feedback.service.station.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID

/**
 * 在独立权威短事务中写入一个有界 Station 历史批次。
 *
 * 既有来源只允许补齐空字段；安全正文、来源时间、规范故障时间和原始密文不可覆盖。
 * 渠道状态以锁定快照和最终条件更新推进，竞争失败立即回滚当前批次。
 */
@Service
class StationLegacyImportApplicationServiceImpl(
    private val repository: StationLegacyRepository,
    private val assembler: StationLegacyRowAssembler,
    private val rawPayloads: StationRawPayloadService,
    private val objectMapper: ObjectMapper,
    private val dirtyMarker: FeedbackListProjectionDirtyMarker,
    private val audits: BusinessAuditService
) : StationLegacyImportApplicationService {
    /** 在独立短事务内导入一个已在事务外解析和限定大小的批次。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun importBatch(
        rows: List<StationLegacyExportRow>,
        runId: String,
        fileSha256: String,
        batchNumber: Int
    ): StationLegacyBatchResult {
        if (rows.isEmpty() || rows.size > StationLegacyRepository.MAX_BATCH_SIZE) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_BATCH_SIZE_INVALID")
        }
        val prepared = rows.map(assembler::prepare)
        val existingByRecord = repository.sourcesByRecordIdsForUpdate(rows.map(StationLegacyExportRow::recordId))
            .associateBy(StationLegacySourcePo::sourceRecordId)
        val insertedRecordIds = linkedSetOf<String>()
        val allSources = existingByRecord.toMutableMap()
        prepared.filter { it.source.recordId !in existingByRecord }.forEach { row ->
            val source = sourceRow(row.source)
            repository.insertSource(source)
            insertedRecordIds += row.source.recordId
            allSources[row.source.recordId] = source
        }

        val sourceIds = allSources.values.map(StationLegacySourcePo::id)
        val contents = repository.contents(sourceIds)
        val channels = repository.channelsForUpdate(sourceIds)
        val rawIds = repository.rawSourceIds(sourceIds)
        val identities = repository.identities(sourceIds)
        val evidenceSourceIds = repository.sourcesWithEvidence(sourceIds)
        existingByRecord.values.forEach { existing ->
            if (existing.id !in contents || existing.id !in channels) {
                throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_LEGACY_EXISTING_FACTS_INCOMPLETE")
            }
        }

        val reasons = mutableMapOf<FeedbackListProjectionDirtyReason, MutableSet<Long>>()
        val decisions = prepared.map { row ->
            val source = allSources[row.source.recordId]
                ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_LEGACY_SOURCE_FACT_MISSING")
            if (row.source.recordId in insertedRecordIds) {
                if (source.id in rawIds) {
                    throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_LEGACY_NEW_SOURCE_RAW_CONFLICT")
                }
                insertNew(source, row, reasons)
            } else {
                val channel = channels[source.id]
                    ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_LEGACY_CHANNEL_FACT_MISSING")
                val content = contents[source.id]
                    ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_LEGACY_CONTENT_FACT_MISSING")
                enrichExisting(source, channel, content, row,
                    identities[source.id].orEmpty(), source.id in evidenceSourceIds, reasons)
            }
        }
        markReasons(reasons)
        val result = decisions.toBatchResult()
        enqueueBatchAudit("STATION_LEGACY_IMPORT_BATCH", runId, fileSha256, batchNumber, result)
        return result
    }

    /** 在独立短事务内锁定一页候选并按 CAS 规则恢复，游标只向前推进。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun recoverPage(
        afterSourceId: Long,
        rowsByRecordId: Map<String, StationLegacyRecoveryRow>,
        runId: String,
        fileSha256: String,
        batchNumber: Int
    ): StationLegacyBatchResult {
        val candidates = repository.recoveryPageForUpdate(afterSourceId)
        if (candidates.isEmpty()) {
            return StationLegacyBatchResult(nextSourceId = afterSourceId)
        }
        val sourceIds = candidates.map(StationLegacyChannelPo::sourceId)
        val sources = repository.sourcesByIds(sourceIds)
        val contents = repository.contents(sourceIds)
        val identities = repository.identities(sourceIds)
        val evidence = repository.sourcesWithEvidence(sourceIds)
        val reasons = mutableMapOf<FeedbackListProjectionDirtyReason, MutableSet<Long>>()
        var matched = 0
        val decisions = candidates.map { channel ->
            val source = sources[channel.sourceId] ?: return@map StationLegacyRowDecision.SKIPPED
            val row = rowsByRecordId[source.sourceRecordId] ?: return@map StationLegacyRowDecision.SKIPPED
            matched++
            val content = contents[source.id]
            if (content?.maskingStatus != "PASS") {
                return@map StationLegacyRowDecision.SKIPPED
            }
            recoverExisting(channel, row.phone, row.photoUrls, identities[source.id].orEmpty(), source.id in evidence, reasons)
        }
        markReasons(reasons)
        val counted = decisions.toBatchResult().copy(
            candidateCount = candidates.size,
            matchedCount = matched,
            nextSourceId = candidates.last().sourceId
        )
        enqueueBatchAudit("STATION_LEGACY_OPERATION_RECOVERY_BATCH", runId, fileSha256, batchNumber, counted)
        return counted
    }

    /** 插入已校验的Station 链路；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun insertNew(
        source: StationLegacySourcePo,
        row: StationLegacyPreparedRow,
        reasons: MutableMap<FeedbackListProjectionDirtyReason, MutableSet<Long>>
    ): StationLegacyRowDecision {
        repository.insertContent(StationLegacyContentPo(
            sourceId = source.id,
            title = row.safeTitle,
            description = row.safeContent,
            problemType = row.source.feedbackType,
            processingStatus = row.source.processingStatus,
            createdAt = row.source.createdAt,
            updatedAt = row.source.updatedAt
        ))
        val identity = storeIdentities(source.id, row.source.sourceUserId, row.phone, emptySet(), reasons)
        val now = LocalDateTime.now()
        val hasEvidence = row.source.photoUrls.isNotEmpty()
        val invalidPhone = row.phone is StationLegacyPhoneDecision.Invalid
        repository.insertChannel(channelRow(source.id, row, identity.phoneAvailable, invalidPhone, hasEvidence, now))
        rawPayloads.store(source.id, rawPayload(row.source), now)
        reasons.add(FeedbackListProjectionDirtyReason.CHANNEL, source.id)
        return when {
            invalidPhone -> StationLegacyRowDecision.INSERTED_INVALID_CONTACT
            else -> StationLegacyRowDecision.INSERTED
        }
    }

    /** 在行级短事务内补齐既有 Station 事实缺失字段；不覆盖已有权威值，并记录对应投影脏原因。 */
    private fun enrichExisting(
        source: StationLegacySourcePo,
        channel: StationLegacyChannelPo,
        content: StationLegacyContentPo,
        row: StationLegacyPreparedRow,
        identityTypes: Set<String>,
        hasEvidence: Boolean,
        reasons: MutableMap<FeedbackListProjectionDirtyReason, MutableSet<Long>>
    ): StationLegacyRowDecision {
        if (hasActiveLease(channel)) {
            return StationLegacyRowDecision.BUSY
        }
        var enriched = false
        if (source.appVersion.isNullOrBlank() && !row.source.appVersion.isNullOrBlank()) {
            val sourceChanged = repository.fillMissingAppVersion(source.id, row.source.appVersion)
            enriched = sourceChanged || enriched
            if (sourceChanged) {
                reasons.add(FeedbackListProjectionDirtyReason.SOURCE, source.id)
            }
        }
        if (needsChannelEnrichment(channel, row.source)) {
            val channelChanged = repository.enrichChannel(channelEnrichment(source.id, row.source))
            enriched = channelChanged || enriched
            if (channelChanged) {
                reasons.add(FeedbackListProjectionDirtyReason.CHANNEL, source.id)
            }
        }
        val identity = storeIdentities(source.id, row.source.sourceUserId, row.phone, identityTypes, reasons)
        val recoverable = channel.ingestStatus in setOf("REVIEW_NEEDED", "FAILED_FINAL") &&
            content.maskingStatus == "PASS"
        val queuePhotos = (channel.ingestStatus == "COMPLETED" || recoverable) &&
            !hasEvidence && channel.pendingPhotoUrls.isNullOrBlank() && row.source.photoUrls.isNotEmpty()
        val invalidCompletedContact = channel.ingestStatus == "COMPLETED" &&
            row.phone is StationLegacyPhoneDecision.Invalid && "PHONE" !in identityTypes
        val contactStatusOutOfSync = channel.ingestStatus == "COMPLETED" &&
            identity.phoneAvailable && channel.contactStatus != "ENCRYPTED"
        if (queuePhotos || recoverable || invalidCompletedContact || contactStatusOutOfSync) {
            applyState(source.id, channel, row.phone, row.source.photoUrls.takeIf { queuePhotos }, identity.phoneAvailable)
            reasons.add(FeedbackListProjectionDirtyReason.CHANNEL, source.id)
            return when {
                recoverable -> StationLegacyRowDecision.RECOVERED
                queuePhotos -> StationLegacyRowDecision.EVIDENCE_QUEUED
                row.phone is StationLegacyPhoneDecision.Invalid -> StationLegacyRowDecision.INVALID_CONTACT
                else -> StationLegacyRowDecision.ENRICHED
            }
        }
        if (row.phone is StationLegacyPhoneDecision.Invalid && "PHONE" !in identityTypes) {
            // 只报告来源里的坏值，不覆盖无关的脱敏/Worker 失败状态。
            return StationLegacyRowDecision.INVALID_CONTACT
        }
        return if (enriched || identity.anyInserted) StationLegacyRowDecision.ENRICHED else StationLegacyRowDecision.SKIPPED
    }

    /** 恢复已存在但处理未完成的 Station 记录；活动租约存在时返回 BUSY，避免与在线 Worker 并发覆盖。 */
    private fun recoverExisting(
        channel: StationLegacyChannelPo,
        phoneValue: String?,
        photos: List<String>,
        identityTypes: Set<String>,
        hasEvidence: Boolean,
        reasons: MutableMap<FeedbackListProjectionDirtyReason, MutableSet<Long>>
    ): StationLegacyRowDecision {
        val phone = assembler.phone(phoneValue)
        val identity = storeIdentities(channel.sourceId, null, phone, identityTypes, reasons)
        val queue = photos.takeIf { it.isNotEmpty() && !hasEvidence && channel.pendingPhotoUrls.isNullOrBlank() }
        // 即使导出里没有新的联系方式或附件，恢复操作也会关闭一行本来已经安全的失败记录。
        // 若在这里强制要求补齐信息，技术上已恢复的行会被无限期搁置。
        applyState(channel.sourceId, channel, phone, queue, identity.phoneAvailable)
        reasons.add(FeedbackListProjectionDirtyReason.CHANNEL, channel.sourceId)
        return StationLegacyRowDecision.RECOVERED
    }

    /** 按当前快照条件推进 Station 渠道状态；联系方式、附件待处理状态和重试时间必须一致更新。 */
    private fun applyState(
        sourceId: Long,
        channel: StationLegacyChannelPo,
        phone: StationLegacyPhoneDecision,
        photos: List<String>?,
        phoneAvailable: Boolean
    ) {
        val now = LocalDateTime.now()
        val pending = photos?.let(objectMapper::writeValueAsString) ?: channel.pendingPhotoUrls
        val hasPending = !pending.isNullOrBlank()
        val invalid = phone is StationLegacyPhoneDecision.Invalid
        repository.applyChannelState(StationLegacyChannelStatePo(
            sourceId = sourceId,
            contactStatus = when {
                phoneAvailable -> "ENCRYPTED"
                invalid -> "NOT_REQUESTED"
                else -> channel.contactStatus
            },
            pendingPhotoUrls = pending,
            ingestStatus = if (hasPending) "RECEIVED" else "COMPLETED",
            processingStage = if (hasPending) "RECEIVED" else "COMPLETED",
            nextRetryAt = if (hasPending) now else null,
            lastErrorCode = if (invalid) "STATION_CONTACT_SOURCE_INVALID" else null,
            lastErrorSummary = if (invalid) {
                if (hasPending) "来源联系方式不符合受控号码规则，已忽略该字段；附件独立恢复"
                else "来源联系方式不符合受控号码规则，已忽略该字段"
            } else null,
            processedAt = if (hasPending) null else now
        ))
    }

    /** 写入缺失的 USER_ID/PHONE 身份；依赖唯一约束去重，并只为实际新增身份标记投影脏数据。 */
    private fun storeIdentities(
        sourceId: Long,
        userId: String?,
        phone: StationLegacyPhoneDecision,
        existingTypes: Set<String>,
        reasons: MutableMap<FeedbackListProjectionDirtyReason, MutableSet<Long>>
    ): IdentityInsertResult {
        var anyInserted = false
        var insertedPhone = false
        if (!userId.isNullOrBlank() && "USER_ID" !in existingTypes && repository.insertIdentity(assembler.userIdentity(sourceId, userId))) {
            anyInserted = true
            reasons.add(FeedbackListProjectionDirtyReason.IDENTITY, sourceId)
        }
        if (phone is StationLegacyPhoneDecision.Valid && "PHONE" !in existingTypes &&
            repository.insertIdentity(assembler.phoneIdentity(sourceId, phone.normalized))) {
            insertedPhone = true
            anyInserted = true
            reasons.add(FeedbackListProjectionDirtyReason.IDENTITY, sourceId)
        }
        return IdentityInsertResult(anyInserted, insertedPhone || "PHONE" in existingTypes)
    }

    /** 根据已校验导入行组装 Station 来源事实，保持既有记录号和时间字段编码兼容。 */
    private fun sourceRow(row: StationLegacyExportRow): StationLegacySourcePo {
        val fault = FaultOccurrenceTimeResolver.proxy(row.createdAt, "STATION_HISTORY_CREATED_PROXY", "SECOND")
        return StationLegacySourcePo(
            publicId = uuidBytes(UUID.randomUUID()),
            sourceRecordId = row.recordId,
            appVersion = row.appVersion,
            sourceEventTime = row.createdAt,
            faultOccurredAt = fault.occurredAt,
            faultTimeRaw = fault.raw,
            faultTimePrecision = fault.precision,
            faultTimeSource = fault.source,
            faultTimeResolution = fault.resolution,
            sourceUpdateTime = row.updatedAt,
            ingestTime = LocalDateTime.now(),
            createdAt = row.createdAt,
            updatedAt = row.updatedAt
        )
    }

    /** 组装 Station 渠道初始状态；根据联系方式和附件证据决定 RECEIVED 或 COMPLETED。 */
    private fun channelRow(
        sourceId: Long,
        row: StationLegacyPreparedRow,
        phoneStored: Boolean,
        invalidPhone: Boolean,
        hasEvidence: Boolean,
        now: LocalDateTime
    ) = StationLegacyChannelPo(
        sourceId = sourceId,
        receiptId = uuidBytes(UUID.randomUUID()),
        productId = row.source.productId,
        sourceProductName = row.source.productName,
        sourceAppId = row.source.appId,
        sourceAppName = row.source.appName,
        sourceUserId = row.source.sourceUserId,
        authenticatedProductCode = row.source.productId,
        sourcePlatform = row.source.platform ?: "UNKNOWN",
        sourceFeedbackType = row.source.feedbackType ?: "UNKNOWN",
        sourceSubChannel = row.source.subChannel ?: "UNKNOWN",
        sourceDescription = row.safeSourceDescription,
        pendingPhotoUrls = row.source.photoUrls.takeIf { it.isNotEmpty() }?.let(objectMapper::writeValueAsString),
        contactStatus = when {
            phoneStored -> "ENCRYPTED"
            invalidPhone -> "NOT_REQUESTED"
            else -> "NOT_PROVIDED"
        },
        ingestStatus = if (hasEvidence) "RECEIVED" else "COMPLETED",
        processingStage = if (hasEvidence) "RECEIVED" else "COMPLETED",
        nextRetryAt = if (hasEvidence) now else null,
        lastErrorCode = if (invalidPhone) "STATION_CONTACT_SOURCE_INVALID" else null,
        lastErrorSummary = if (invalidPhone) "来源联系方式不符合受控号码规则，已忽略该字段" else null,
        requestFingerprint = sha256(row.source.recordId.toByteArray()),
        processedAt = if (hasEvidence) null else now,
        createdAt = row.source.createdAt,
        updatedAt = row.source.updatedAt
    )

    /** 只组装允许补齐的渠道字段，不生成覆盖既有权威字段的更新值。 */
    private fun channelEnrichment(sourceId: Long, row: StationLegacyExportRow) = StationLegacyChannelEnrichmentPo(
        sourceId, row.productId, row.productName, row.appId, row.appName, row.sourceUserId
    )

    /** 判断既有渠道是否存在可安全补齐的空字段，避免无变化更新和审计噪声。 */
    private fun needsChannelEnrichment(channel: StationLegacyChannelPo, row: StationLegacyExportRow): Boolean =
        (channel.productId == "UNKNOWN" && row.productId != "UNKNOWN") ||
            (channel.sourceProductName.isNullOrBlank() && !row.productName.isNullOrBlank()) ||
            (channel.sourceAppId.isNullOrBlank() && !row.appId.isNullOrBlank()) ||
            (channel.sourceAppName.isNullOrBlank() && !row.appName.isNullOrBlank()) ||
            (channel.sourceUserId.isNullOrBlank() && !row.sourceUserId.isNullOrBlank())

    /** 判断历史记录租约是否仍有效；活动租约必须阻止导入恢复并发写入。 */
    private fun hasActiveLease(channel: StationLegacyChannelPo): Boolean =
        channel.leaseExpiresAt?.isAfter(LocalDateTime.now()) == true

    /** 按前置状态条件更新 Station 链路；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun markReasons(reasons: Map<FeedbackListProjectionDirtyReason, Set<Long>>) {
        reasons.forEach { (reason, ids) ->
            if (ids.isNotEmpty()) {
                dirtyMarker.mark(FeedbackListProjectionDirtyCommand(ids.sorted(), reason))
            }
        }
    }

    /** 把来源 ID 加入指定投影脏原因集合；使用 Set 去重，批次结束后统一有界提交。 */
    private fun MutableMap<FeedbackListProjectionDirtyReason, MutableSet<Long>>.add(
        reason: FeedbackListProjectionDirtyReason,
        sourceId: Long
    ) {
        getOrPut(reason, ::linkedSetOf).add(sourceId)
    }

    /** 汇总导入行决策为稳定批次计数，保证成功、补齐、跳过、忙碌和失败分类互斥。 */
    private fun List<StationLegacyRowDecision>.toBatchResult() = StationLegacyBatchResult(
        inserted = count { it == StationLegacyRowDecision.INSERTED || it == StationLegacyRowDecision.INSERTED_INVALID_CONTACT },
        enriched = count { it == StationLegacyRowDecision.ENRICHED },
        evidenceQueued = count { it == StationLegacyRowDecision.EVIDENCE_QUEUED },
        recovered = count { it == StationLegacyRowDecision.RECOVERED },
        invalidContacts = count { it == StationLegacyRowDecision.INVALID_CONTACT || it == StationLegacyRowDecision.INSERTED_INVALID_CONTACT },
        skipped = count { it == StationLegacyRowDecision.SKIPPED },
        busySkipped = count { it == StationLegacyRowDecision.BUSY }
    )

    /** 写入批次级安全审计，只包含文件摘要、计数和稳定结果码，不记录原始行文本。 */
    private fun enqueueBatchAudit(
        operation: String,
        runId: String,
        fileSha256: String,
        batchNumber: Int,
        result: StationLegacyBatchResult
    ) {
        audits.enqueue(BusinessAuditEvent(
            null, operation, "SECURITY", "SUCCESS", "STATION_EXPORT", fileSha256.take(32),
            "$runId-$batchNumber", null,
            mapOf(
                "fileSha256" to fileSha256,
                "batchNumber" to batchNumber,
                "inserted" to result.inserted,
                "enriched" to result.enriched,
                "evidenceQueued" to result.evidenceQueued,
                "recovered" to result.recovered,
                "invalidContacts" to result.invalidContacts,
                "skipped" to result.skipped,
                "busySkipped" to result.busySkipped,
                "candidateCount" to result.candidateCount,
                "matchedCount" to result.matchedCount
            )
        ))
    }

    /** 把导入行序列化并加密为 Station 原始载荷；明文仅在事务外短暂存在。 */
    private fun rawPayload(row: StationLegacyExportRow) = StationLegacyRawPayload(
        sourceRecordId = row.recordId,
        title = row.title,
        content = row.content,
        description = row.sourceDescription,
        productId = row.productId,
        productName = row.productName,
        appId = row.appId,
        appName = row.appName,
        appVersion = row.appVersion,
        sourceUserId = row.sourceUserId,
        phone = row.phone,
        photoUrlList = row.photoUrls
    )

    /** 把 UUID 转换为数据库兼容字节：Station 链路；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun uuidBytes(uuid: UUID): ByteArray = ByteBuffer.allocate(16)
        .putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    /** 计算稳定 SHA-256 摘要：Station 链路；外部 I/O 和高成本计算留在事务外，写入依赖短事务与条件更新。 */
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
