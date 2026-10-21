package com.feedback.controller.admin

import com.feedback.service.cluster.HistoricalClusterShadowReport
import com.feedback.service.cluster.HistoricalShadowAssignment
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.math.BigDecimal

@Schema(description = "问题簇 v2 真实历史只读影子评测请求")
data class ClusterShadowEvaluationRequest(
    @field:Min(0)
    @field:Schema(description = "稳定 source ID 游标；首次执行传 0", example = "0")
    val afterSourceId: Long,
    @field:Min(1)
    @field:Max(100)
    @field:Schema(description = "本批最多读取的历史反馈数，上限 100", example = "100")
    val limit: Int
)

@Schema(description = "单条历史反馈的当前归属与 v2 影子建议对比")
data class ClusterShadowAssignmentResponse(
    @field:Schema(description = "反馈 source ID") val sourceId: Long,
    @field:Schema(description = "当前活动 MAIN 簇 ID；未归簇时为空") val currentClusterId: Long?,
    @field:Schema(description = "v2 问题分类状态", example = "ACTIONABLE") val status: String,
    @field:Schema(description = "v2 稳定簇键；非业务终态时为空") val proposedClusterKey: String?,
    @field:Schema(description = "v2 稳定簇名；非业务终态时为空") val proposedClusterName: String?,
    @field:Schema(description = "影子决策类型", example = "LINK_EXISTING") val decision: String,
    @field:Schema(description = "候选相似度；无候选或终态时可为空") val score: BigDecimal?,
    @field:Schema(description = "可审计的决策原因") val reason: String,
    @field:Schema(description = "是否因历史人工结论而冻结") val manualLocked: Boolean
)

@Schema(description = "问题簇 v2 真实历史只读影子评测结果")
data class ClusterShadowEvaluationResponse(
    @field:Schema(description = "确定性算法版本") val algorithmVersion: String,
    @field:Schema(description = "完成评测的有效反馈数") val processedCount: Int,
    @field:Schema(description = "本批当前 MAIN 去重簇数") val currentClusterCount: Int,
    @field:Schema(description = "v2 建议的去重业务簇数") val proposedClusterCount: Int,
    @field:Schema(description = "低信息、应用未解析或歧义等非业务终态数") val nonActionableCount: Int,
    @field:Schema(description = "当前 MAIN 与 v2 建议之间同簇关系发生变化的 pair 数") val pairwiseChangedCount: Int,
    @field:Schema(description = "当前不同簇但 v2 建议同簇的 pair 数，需重点审核误合并风险") val pairwiseMergedCount: Int,
    @field:Schema(description = "当前同簇但 v2 建议拆分的 pair 数，包含非业务终态移出") val pairwiseSplitCount: Int,
    @field:Schema(description = "下一批稳定 source ID 游标") val nextSourceId: Long,
    @field:Schema(description = "是否可能还有下一批；不代表系统会自动执行") val mayHaveMore: Boolean,
    @field:Schema(description = "逐条只读对比结果") val assignments: List<ClusterShadowAssignmentResponse>
)

fun HistoricalClusterShadowReport.response() = ClusterShadowEvaluationResponse(
    algorithmVersion = algorithmVersion,
    processedCount = processedCount,
    currentClusterCount = currentClusterCount,
    proposedClusterCount = proposedClusterCount,
    nonActionableCount = nonActionableCount,
    pairwiseChangedCount = pairwiseChangedCount,
    pairwiseMergedCount = pairwiseMergedCount,
    pairwiseSplitCount = pairwiseSplitCount,
    nextSourceId = nextSourceId,
    mayHaveMore = mayHaveMore,
    assignments = assignments.map(HistoricalShadowAssignment::response)
)

private fun HistoricalShadowAssignment.response() = ClusterShadowAssignmentResponse(
    sourceId, currentClusterId, status.name, proposedClusterKey, proposedClusterName,
    decision, score, reason, manualLocked
)
