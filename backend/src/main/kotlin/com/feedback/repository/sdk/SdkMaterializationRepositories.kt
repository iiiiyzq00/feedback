package com.feedback.repository.sdk

import com.feedback.model.po.sdk.*
import com.feedback.model.sdk.*

/** SDK 接入处理信封仓储契约。 */
interface SdkProcessingEnvelopeRepository {
    /** 按受控条件查询 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun get(id: Long): SdkProcessingEnvelopePo
    /** 按上限查询待处理的SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findPending(limit: Int): List<SdkProcessingEnvelopePo>
    /** 按受控条件查询 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findByBusinessKey(appId: String, clientFeedbackId: String): SdkProcessingEnvelopePo?
    /** 按受控条件查询 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findByReceiptId(receiptId: ByteArray): SdkProcessingEnvelopePo?
    /** 插入已校验的SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insert(row: SdkProcessingEnvelopePo): SdkProcessingEnvelopePo
    /** 按受控条件查询 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findBySourceIds(sourceIds: List<Long>): List<SdkProcessingEnvelopePo>
    /** 保存并校验持久化结果：SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun saveState(envelope: SdkProcessingEnvelopePo)
}

/** SDK 来源事实物化仓储；负责按业务记录定位及仅接受更新事件的条件写入。 */
interface SdkFeedbackSourceMutationRepository {
    /** 按受控条件查询 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun find(id: Long): SdkFeedbackSourcePo?
    /** 按受控条件查询 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findByRecordId(sourceRecordId: String): SdkFeedbackSourcePo?
    /** 插入已校验的SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insert(row: SdkFeedbackSourcePo): SdkFeedbackSourcePo
    /** 以条件更新推进SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun updateNewerEvent(row: SdkFeedbackSourcePo): Boolean
}

/** SDK 正文物化仓储；保存已通过隐私处理的正文及处理状态。 */
interface SdkFeedbackContentMutationRepository {
    /** 按来源唯一键幂等写入 SDK 正文；调用方负责保证正文已完成受控处理。 */
    fun upsert(row: SdkFeedbackContentPo)
}

/** SDK 渠道物化仓储；提供有界扫描、渠道幂等写入与受控诊断更新。 */
interface SdkChannelMutationRepository {
    /** 按游标和显式上限分页查询 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findPageAfterId(afterSourceId: Long, limit: Int): List<SdkChannelPo>
    /** 按唯一业务键幂等写入 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun upsert(row: SdkChannelPo)
    /** 以条件更新推进SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun updateControlledDiagnostics(row: SdkChannelPo): Boolean
    /** 按唯一业务键幂等写入 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun upsertControlledDiagnostics(rows: List<SdkChannelPo>)
}

/** SDK 身份物化仓储；批量查询和写入来源身份，集合必须去重且不超过四百条。 */
interface SdkIdentityMutationRepository {
    /** 按受控条件查询 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findBySourceIds(sourceIds: List<Long>): List<SdkIdentityPo>
    /** 在显式批量上限内幂等写入 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun upsertBatch(rows: List<SdkIdentityPo>)
}

/** SDK 附件接入仓储；按客户端附件 ID 保证幂等，并在解密成功后关联正式来源。 */
interface SdkAttachmentMutationRepository {
    /** 按受控条件查询 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findByClientAttachmentId(ingestId: Long, clientAttachmentId: String): SdkAttachmentIngressPo?
    /** 插入已校验的SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insertIngress(row: SdkAttachmentIngressPo): SdkAttachmentIngressPo
    /** 建立已校验的关联：SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun linkToSource(ingestId: Long, sourceId: Long): Int
}

/** SDK 传输遥测仓储；持久化裁剪后的事件与请求指标，不接受密文或原始正文。 */
interface SdkTelemetryRepository {
    /** 插入已校验的SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insertTransportEvent(row: SdkTransportTelemetryPo)
    /** 插入已校验的SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insertTransportEvents(rows: List<SdkTransportTelemetryPo>)
    /** 插入已校验的SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun insertRequestMetric(row: SdkRequestMetricPo)
    /** 按保留期和批量上限清理SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun purgeExpiredRequestMetrics(limit: Int): Int
}

/** SDK 授权联系方式仓储；只读取保留期内记录，并在授权成立后关联反馈来源。 */
interface SdkAuthorizedContactMutationRepository {
    /** 按受控条件查询 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun findRetainedByIngestId(ingestId: Long): List<SdkAuthorizedContactPo>
    /** 按唯一业务键幂等写入 SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun upsert(row: SdkAuthorizedContactPo)
    /** 建立已校验的关联：SDK 物化记录；集合参数必须有界，带 ForUpdate/claim 的方法要求调用方处于明确短事务。 */
    fun linkToSource(ingestId: Long, sourceId: Long): Int
}
