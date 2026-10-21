package com.feedback.service.query.detail

import com.feedback.application.port.feedback.detail.*
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.security.FieldScopedResponse
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Schema(description = "反馈详情时间轴事件；只包含已通过数据范围校验的安全业务事实")
data class FeedbackTimelineEventResponse(
    @field:Schema(description = "事件稳定标识", example = "cluster-128")
    val id: String,
    @field:Schema(description = "事件发生时间，Asia/Shanghai；来源无可靠时间时为空；字段策略拒绝时省略", example = "2026-08-30T09:05:00")
    val time: LocalDateTime?,
    @field:Schema(description = "事件名称；字段策略拒绝时省略", example = "问题归簇")
    val action: String,
    @field:Schema(description = "脱敏后的事件说明；不包含原始密文、令牌或附件正文；字段策略拒绝时省略", example = "簇 #3 · APPROVED")
    val detail: String?,
    @field:Schema(description = "前端时间轴展示类型；当前固定为 primary；字段策略拒绝时省略", allowableValues = ["primary"], example = "primary")
    val type: String = "primary",
    override val fieldPolicy: DataFieldPolicy
) : FieldScopedResponse

/**
 * 时间轴组装所需的已授权事实集合。
 *
 * 持久化层只读取单表事实；本对象是跨表关系在应用层汇合的唯一边界。
 */
data class FeedbackTimelineFacts(
    val source: FeedbackSourceSnapshot,
    val content: FeedbackContentSnapshot?,
    val ai: FeedbackAiSnapshot?,
    val importBatch: FeedbackImportBatchSnapshot?,
    val importRow: FeedbackTimelineImportRowSnapshot?,
    val clusterLinks: List<FeedbackTimelineClusterSnapshot>,
    val fieldHistory: List<FeedbackFieldHistoryResponse>,
    val fieldPolicy: DataFieldPolicy
)

/** 纯内存组装时间轴，保持历史字段、文案和按时间升序的兼容行为。 */
@Component
class FeedbackTimelineAssembler {
    fun assemble(facts: FeedbackTimelineFacts): List<FeedbackTimelineEventResponse> {
        val events = mutableListOf<FeedbackTimelineEventResponse>()
        fun event(id: String, time: LocalDateTime?, action: String, detail: String?) =
            FeedbackTimelineEventResponse(
                id = id,
                time = time,
                action = action,
                detail = detail,
                fieldPolicy = facts.fieldPolicy
            )
        events += event(
            id = "ingest",
            time = facts.source.ingestTime,
            action = "原始数据导入",
            detail = listOfNotNull(
                facts.importBatch?.batchId?.let { "批次 $it" },
                facts.importRow?.sheetName?.let { "工作表 $it" },
                facts.importRow?.rowNumber?.let { "第 $it 行" }
            ).ifEmpty { listOf("实时接入") }.joinToString(" · ")
        )
        facts.content?.let { content ->
            events += event("mapping", content.createdAt, "字段映射", "规则版本 ${content.mappingRuleVersion ?: "-"}")
            events += event("mask", content.createdAt, "脱敏处理", "状态 ${content.maskingStatus}; 仅展示脱敏后结果")
        }
        facts.ai?.aiProcessedAt?.let { processedAt ->
            events += event("ai", processedAt, "AI分类", "模型 ${facts.ai.aiModelVersion ?: "-"}")
        }
        facts.clusterLinks.forEach { link ->
            events += event(
                "cluster-${link.id}",
                link.createdAt,
                "问题归簇",
                "簇 #${link.clusterId} · ${link.reviewStatus} · 相似度 ${link.matchSimilarity ?: "-"} · " +
                    "${link.decisionVersion ?: "-"} · ${link.decisionReason ?: "无原文证据"}"
            )
        }
        facts.fieldHistory.forEach { history ->
            events += event(
                "field-${history.id}",
                history.createdAt,
                if (facts.fieldPolicy.permits("changeType") && history.changeType == "MANUAL_CORRECTION") {
                    "人工修正"
                } else {
                    "字段转换"
                },
                fieldHistoryDetail(history, facts.fieldPolicy)
            )
        }
        // 旧实现按 time.toString() 稳定排序；空时间的字符串为 "null"，因此仍排在有效时间之后。
        return events.sortedBy { it.time?.toString() ?: "null" }
    }

    /**
     * 字段历史值必须按其原始字段权限净化后才能进入通用 detail，避免通过字段别名绕过策略。
     */
    private fun fieldHistoryDetail(history: FeedbackFieldHistoryResponse, policy: DataFieldPolicy): String? {
        val fieldName = history.fieldName.takeIf { policy.permits("fieldName") }
        val oldValue = if (policy.permits("oldValue")) history.oldValue ?: "-" else null
        val newValue = if (policy.permits("newValue")) history.newValue ?: "-" else null
        val transition = when {
            oldValue != null && newValue != null -> "$oldValue → $newValue"
            oldValue != null -> "$oldValue → 已隐藏"
            newValue != null -> "已隐藏 → $newValue"
            else -> null
        }
        val change = when {
            fieldName != null && transition != null -> "$fieldName: $transition"
            fieldName != null -> fieldName
            else -> transition
        }
        val reason = history.changeReason.takeIf { policy.permits("changeReason") }
        return listOfNotNull(change, reason).joinToString(" · ").ifBlank { null }
    }
}
