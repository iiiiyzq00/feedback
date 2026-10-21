package com.feedback.repository.sdk

import com.feedback.model.sdk.SdkIngest
import java.util.Optional

/** SDK 加密接入信封仓储契约；Mapper 仅对 MyBatis 实现可见。 */
interface SdkIngestRepository {
/** 按主键参数化查询记录；未命中语义由上层 Repository 统一决定。 */
    fun findById(id: Long): Optional<SdkIngest>
/** 执行 findByAppIdAndClientFeedbackId 数据访问；输入集合必须有界，锁、条件更新和影响行数语义不得弱化。 */
    fun findByAppIdAndClientFeedbackId(appId: String, clientFeedbackId: String): SdkIngest?
/** 按固定长度回执查询 SDK 信封；回执格式必须在进入 Mapper 前完成校验。 */
    fun findByReceiptId(receiptId: ByteArray): SdkIngest?
/** 读取有界待处理 SDK 信封集合，调用方不得据此启动无界并发。 */
    fun findPendingEnvelopes(): List<SdkIngest>
/** 保存已校验领域记录；实现必须校验影响行数并返回权威持久化结果。 */
    fun save(row: SdkIngest): SdkIngest
/** 立即提交当前 SDK 信封写入以暴露唯一约束竞争；不得包围外部安全检查。 */
    fun saveAndFlush(row: SdkIngest): SdkIngest
}
