package com.feedback.repository.impl.sdk

import com.feedback.model.sdk.SdkIngest
import com.feedback.mapper.core.CoreSdkIngestMapper
import com.feedback.repository.sdk.SdkIngestRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
/** SDK 接入信封核心仓储的 MyBatis 实现；保持业务键、回执与待处理查询兼容。 */
class MyBatisSdkIngestRepositoryImpl(private val mapper: CoreSdkIngestMapper) : SdkIngestRepository {
    /** 按受控条件查询 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findById(id: Long): Optional<SdkIngest> = Optional.ofNullable(mapper.findById(id))
    /** 按受控条件查询 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findByAppIdAndClientFeedbackId(appId: String, clientFeedbackId: String) = mapper.findByBusinessKey(appId, clientFeedbackId)
    /** 按受控条件查询 SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findByReceiptId(receiptId: ByteArray) = mapper.findByReceiptId(receiptId)
    /** 按上限查询待处理的SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun findPendingEnvelopes(): List<SdkIngest> = mapper.findPending()
    /** 保存并校验持久化结果：SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun save(row: SdkIngest): SdkIngest {
        val affected = if (row.id == 0L) mapper.insert(row) else mapper.update(row)
        check(affected in 0..1) { "SDK_INGEST_SAVE_AFFECTED_ROWS_INVALID" }
        return row
    }
    /** 保存并校验持久化结果：SDK 链路；仅通过受控 Mapper 访问数据库，并校验集合上限、重复值和影响行数。 */
    override fun saveAndFlush(row: SdkIngest): SdkIngest = save(row)
}
