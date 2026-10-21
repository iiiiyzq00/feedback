package com.feedback.repository.impl.rca

import com.feedback.agent.rca.tools.HistoricalCasePort
import com.feedback.agent.rca.tools.HistoricalCaseQuery
import com.feedback.agent.rca.tools.HistoricalCaseRecord
import com.feedback.agent.rca.tools.HistoricalCaseRow
import com.feedback.agent.rca.tools.UpstreamEnvelope
import com.feedback.mapper.rca.RcaHistoricalCaseMapper
import org.springframework.stereotype.Repository

/**
 * 历史案例端口的 MyBatis 实现：读本平台的 `t_root_cause_analysis`。
 *
 * ## 「历史案例」在本系统里是本地事实，不是上游系统
 *
 * [UpstreamEnvelope] 是为**跨系统**调用设计的，它带 `providerRequestId`（上游请求 ID）
 * 与 `watermark`（数据水位）两个字段。本实现读的是本平台自己的库，
 * 因此这两个字段**恒为 null**，而且这不是「暂时没填」：
 *
 * - `providerRequestId`：本地库读没有上游请求，编一个 ID 出来会让
 *   `RcaToolRecovery` 的按请求 ID 对账去查一个不存在的上游单据；
 * - `watermark`：水位表达的是「上游数据截至何时」，本地读的事实就是本库当前状态，
 *   没有「上游还没同步到」这种情况，因此不该伪造一个水位——
 *   伪造的水位会让后续的时效判定在一个假基准上做 STALE 判断。
 *
 * ## 查不到案例是**正常结果**，不是失败
 *
 * 没有匹配返回 `HistoricalCaseRecord(rows = emptyList())`，经
 * `HistoricalCaseTool` 归为 `NO_VERIFIED_CASE`——该状态在
 * `ToolResultStatus` 里是 `evidenceGap = false` 的**弱缺口**：
 * 缺少历史先验不影响现场取证。所以这里绝不能返回
 * `UpstreamEnvelope.failed(...)`：那会被翻译成 `UNKNOWN`（`evidenceGap = true`），
 * 把一个「本来就没有先例」的常态说成「这次取证有缺口」，从而抬高每个 Episode 的缺口计数。
 */
@Repository
class MyBatisRcaHistoricalCaseRepositoryImpl(
    private val mapper: RcaHistoricalCaseMapper
) : HistoricalCasePort {

    override fun fetch(query: HistoricalCaseQuery): UpstreamEnvelope<HistoricalCaseRecord> {
        val rows = mapper.findConfirmedCases(
            signatureHint = query.signatureHint,
            component = query.component,
            limit = query.topK.coerceIn(1, MAX_TOP_K)
        ).map { row ->
            HistoricalCaseRow(
                incidentId = row.incidentId,
                title = row.title,
                confirmedRootCause = row.confirmedRootCause,
                // 该表没有「修复版本」列，如实取 null，不构造一个出来。
                fixVersion = null,
                verificationPassed = row.verificationPassed,
                confirmedAt = row.confirmedAt
            )
        }
        return UpstreamEnvelope.ok(HistoricalCaseRecord(rows))
    }

    private companion object {
        /**
         * 召回上限，与 `HistoricalCaseTool` 对 `topK` 的钳制保持同一上限。
         * 两处都钳是刻意的：工具层挡住模型给的超大值，这一层挡住任何新调用方。
         */
        const val MAX_TOP_K = 20
    }
}
