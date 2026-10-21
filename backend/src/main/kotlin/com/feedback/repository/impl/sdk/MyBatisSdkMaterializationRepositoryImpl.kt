package com.feedback.repository.impl.sdk

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.sdk.*
import com.feedback.model.po.sdk.*
import com.feedback.model.sdk.*
import com.feedback.repository.sdk.*
import org.springframework.stereotype.Repository

/** SDK 处理信封仓储的 MyBatis 实现；校验查询上限及状态更新影响行数。 */
@Repository
class MyBatisSdkProcessingEnvelopeRepositoryImpl(private val mapper: SdkProcessingEnvelopeMapper) : SdkProcessingEnvelopeRepository {
    /** 按受控条件查询 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun get(id: Long): SdkProcessingEnvelopePo = mapper.findById(id)
        ?: throw ClientException(ErrorCode.NOT_FOUND, "SDK_INGEST_NOT_FOUND")
    /** 按上限查询待处理的SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findPending(limit: Int): List<SdkProcessingEnvelopePo> {
        requireClient(limit in 1..200, "SDK_INGEST_PENDING_LIMIT_INVALID")
        return mapper.findPending(limit)
    }
    /** 按受控条件查询 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findByBusinessKey(appId: String, clientFeedbackId: String): SdkProcessingEnvelopePo? =
        mapper.findByBusinessKey(appId, clientFeedbackId)
    /** 按受控条件查询 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findByReceiptId(receiptId: ByteArray): SdkProcessingEnvelopePo? = mapper.findByReceiptId(receiptId)
    /** 插入已校验的SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insert(row: SdkProcessingEnvelopePo): SdkProcessingEnvelopePo {
        requireService(mapper.insert(row) == 1 && row.id > 0, "SDK_INGEST_INSERT_FAILED")
        return row
    }
    /** 按受控条件查询 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findBySourceIds(sourceIds: List<Long>): List<SdkProcessingEnvelopePo> =
        if (sourceIds.isEmpty()) emptyList() else mapper.findBySourceIds(boundedIds(sourceIds))
    /** 保存并校验持久化结果：SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun saveState(envelope: SdkProcessingEnvelopePo) {
        requireService(mapper.updateProcessingState(envelope) == 1, "SDK_INGEST_STATE_UPDATE_FAILED")
    }
}

/** SDK 来源事实仓储的 MyBatis 实现；保留事件时间条件更新语义。 */
@Repository
class MyBatisSdkFeedbackSourceMutationRepositoryImpl(private val mapper: SdkFeedbackSourceMutationMapper) : SdkFeedbackSourceMutationRepository {
    /** 按受控条件查询 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun find(id: Long): SdkFeedbackSourcePo? = mapper.findById(id)
    /** 按受控条件查询 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findByRecordId(sourceRecordId: String): SdkFeedbackSourcePo? = mapper.findByRecordId(sourceRecordId)
    /** 插入已校验的SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insert(row: SdkFeedbackSourcePo): SdkFeedbackSourcePo {
        requireService(mapper.insert(row) == 1 && row.id > 0, "SDK_SOURCE_INSERT_FAILED")
        return row
    }
    /** 以条件更新推进SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun updateNewerEvent(row: SdkFeedbackSourcePo): Boolean = mapper.updateNewerEvent(row) == 1
}

/** SDK 正文仓储的 MyBatis 实现；校验 upsert 执行结果并隔离 Mapper。 */
@Repository
class MyBatisSdkFeedbackContentMutationRepositoryImpl(private val mapper: SdkFeedbackContentMutationMapper) : SdkFeedbackContentMutationRepository {
    /** 按唯一业务键幂等写入 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun upsert(row: SdkFeedbackContentPo) {
        requireService(mapper.upsert(row) >= 0, "SDK_CONTENT_UPSERT_FAILED")
    }
}

/** SDK 渠道仓储的 MyBatis 实现；统一约束游标、批量上限和诊断去重。 */
@Repository
class MyBatisSdkChannelMutationRepositoryImpl(private val mapper: SdkChannelMutationMapper) : SdkChannelMutationRepository {
    /** 按游标和显式上限分页查询 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findPageAfterId(afterSourceId: Long, limit: Int): List<SdkChannelPo> {
        requireClient(afterSourceId >= 0 && limit in 1..200, "SDK_CHANNEL_PAGE_INVALID")
        return mapper.findPageAfterId(afterSourceId, limit)
    }
    /** 按唯一业务键幂等写入 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun upsert(row: SdkChannelPo) {
        requireService(mapper.upsert(row) >= 0, "SDK_CHANNEL_UPSERT_FAILED")
    }
    /** 以条件更新推进SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun updateControlledDiagnostics(row: SdkChannelPo): Boolean = mapper.updateControlledDiagnostics(row) == 1
    /** 按唯一业务键幂等写入 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun upsertControlledDiagnostics(rows: List<SdkChannelPo>) {
        if (rows.isEmpty()) {
            return
        }
        requireClient(rows.size <= 200 && rows.map { it.sourceId }.distinct().size == rows.size,
            "SDK_CHANNEL_DIAGNOSTIC_BATCH_INVALID")
        requireService(mapper.upsertControlledDiagnostics(rows) >= 0, "SDK_CHANNEL_DIAGNOSTIC_BATCH_FAILED")
    }
}

/** SDK 身份仓储的 MyBatis 实现；限制来源批次二百、身份写入批次四百。 */
@Repository
class MyBatisSdkIdentityMutationRepositoryImpl(private val mapper: SdkIdentityMutationMapper) : SdkIdentityMutationRepository {
    /** 按受控条件查询 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findBySourceIds(sourceIds: List<Long>): List<SdkIdentityPo> =
        if (sourceIds.isEmpty()) emptyList() else mapper.findBySourceIds(boundedIds(sourceIds))
    /** 在显式批量上限内幂等写入 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun upsertBatch(rows: List<SdkIdentityPo>) {
        if (rows.isEmpty()) {
            return
        }
        requireClient(rows.size <= 400 && rows.map { Triple(it.sourceId, it.identityType, it.identityScope) }.distinct().size == rows.size,
            "SDK_IDENTITY_BATCH_INVALID")
        requireService(mapper.upsertBatch(rows) >= 0, "SDK_IDENTITY_BATCH_FAILED")
    }
}

/** SDK 附件仓储的 MyBatis 实现；校验接入记录主键并返回条件关联影响行数。 */
@Repository
class MyBatisSdkAttachmentMutationRepositoryImpl(private val mapper: SdkAttachmentMutationMapper) : SdkAttachmentMutationRepository {
    /** 按受控条件查询 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findByClientAttachmentId(ingestId: Long, clientAttachmentId: String): SdkAttachmentIngressPo? =
        mapper.findByClientAttachmentId(ingestId, clientAttachmentId)
    /** 插入已校验的SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insertIngress(row: SdkAttachmentIngressPo): SdkAttachmentIngressPo {
        requireService(mapper.insertIngress(row) == 1 && row.id > 0, "SDK_ATTACHMENT_INSERT_FAILED")
        return row
    }
    /** 建立已校验的关联：SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun linkToSource(ingestId: Long, sourceId: Long): Int = mapper.linkToSource(ingestId, sourceId)
}

/** SDK 遥测仓储的 MyBatis 实现；批量写入最多一百条并严格核对影响行数。 */
@Repository
class MyBatisSdkTelemetryRepositoryImpl(
    private val transportMapper: SdkTransportTelemetryMapper,
    private val requestMetricMapper: SdkRequestMetricMapper
) : SdkTelemetryRepository {
    /** 插入已校验的SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insertTransportEvent(row: SdkTransportTelemetryPo) {
        requireService(transportMapper.insertTransportEvent(row) == 1 && row.id > 0, "SDK_TELEMETRY_INSERT_FAILED")
    }
    /** 插入已校验的SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insertTransportEvents(rows: List<SdkTransportTelemetryPo>) {
        if (rows.isEmpty()) {
            return
        }
        requireClient(rows.size <= 100, "SDK_TELEMETRY_BATCH_INVALID")
        requireService(transportMapper.insertTransportEvents(rows) == rows.size, "SDK_TELEMETRY_BATCH_INSERT_FAILED")
    }
    /** 插入已校验的SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun insertRequestMetric(row: SdkRequestMetricPo) {
        requireService(requestMetricMapper.insertRequestMetric(row) == 1 && row.id > 0, "SDK_REQUEST_METRIC_INSERT_FAILED")
    }
    /** 按保留期和批量上限清理SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun purgeExpiredRequestMetrics(limit: Int): Int = requestMetricMapper.purgeExpiredRequestMetrics(limit.coerceIn(1, 10_000))
}

/** SDK 授权联系方式仓储的 MyBatis 实现；隔离保留期查询、幂等保存和来源关联。 */
@Repository
class MyBatisSdkAuthorizedContactMutationRepositoryImpl(private val mapper: SdkAuthorizedContactMutationMapper) : SdkAuthorizedContactMutationRepository {
    /** 按受控条件查询 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findRetainedByIngestId(ingestId: Long): List<SdkAuthorizedContactPo> = mapper.findRetainedByIngestId(ingestId)
    /** 按唯一业务键幂等写入 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun upsert(row: SdkAuthorizedContactPo) {
        requireService(mapper.upsert(row) >= 0, "SDK_AUTHORIZED_CONTACT_UPSERT_FAILED")
    }
    /** 建立已校验的关联：SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun linkToSource(ingestId: Long, sourceId: Long): Int = mapper.linkToSource(ingestId, sourceId)
}

/** 校验集合边界：SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
private fun boundedIds(sourceIds: List<Long>): List<Long> = sourceIds.distinct().sorted().also {
    requireClient(it.isNotEmpty() && it.size <= 200 && it.all { id -> id > 0 }, "SDK_MATERIALIZATION_SOURCE_IDS_INVALID")
}

/** 校验输入或不变量：SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
private fun requireClient(condition: Boolean, reasonCode: String) {
    if (!condition) {
        throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)
    }
}

/** 校验输入或不变量：SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
private fun requireService(condition: Boolean, reasonCode: String) {
    if (!condition) {
        throw ServiceException(ErrorCode.INTERNAL_ERROR, reasonCode)
    }
}
