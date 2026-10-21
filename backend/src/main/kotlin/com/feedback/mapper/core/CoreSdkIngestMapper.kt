package com.feedback.mapper.core

import com.feedback.model.po.core.*
import com.feedback.model.sdk.SdkIngest
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** CoreSdkIngestMapper 参数化 Mapper；仅供 Repository 实现调用并保持单表数据访问职责。 */
@Mapper
interface CoreSdkIngestMapper {
/** 按主键参数化查询记录；未命中语义由上层 Repository 统一决定。 */
    fun findById(@Param("id") id: Long): SdkIngest?
/** 按稳定业务幂等键查询 SDK 信封，用于重放和唯一键竞争后的权威复核。 */
    fun findByBusinessKey(@Param("appId") appId: String, @Param("clientFeedbackId") clientFeedbackId: String): SdkIngest?
/** 按固定长度回执查询 SDK 信封；回执格式必须在进入 Mapper 前完成校验。 */
    fun findByReceiptId(@Param("receiptId") receiptId: ByteArray): SdkIngest?
/** 查询有明确上限的待处理 SDK 信封，供 Worker 分批领取或处理。 */
    fun findPending(): List<SdkIngest>
/** 插入已校验记录并返回影响行数，Repository 必须核对主键回填和写入结果。 */
    fun insert(@Param("row") row: SdkIngest): Int
/** 按主键或版本条件更新记录并返回影响行数，零行不得被静默视为成功。 */
    fun update(@Param("row") row: SdkIngest): Int
}
