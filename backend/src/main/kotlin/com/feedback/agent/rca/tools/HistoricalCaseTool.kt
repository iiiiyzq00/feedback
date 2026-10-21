package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.EvidenceType
import com.feedback.agent.rca.domain.RcaToolName
import com.feedback.agent.rca.domain.ToolObservation
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.domain.ToolResultStatus

/**
 * 历史案例工具。失败语义：`NO_VERIFIED_CASE`、`STALE`。
 *
 * 这条工具是三条失败复盘的直接产物，使用限制比其它六条都严格：
 *
 * 1. **只召回经过发布后验证有效的案例**。上游返回未验证的案例时，[HistoricalCaseRecord.hasVerifiedCase]
 *    为 false，本工具直接返回 `NO_VERIFIED_CASE`，不让「疑似修过」的案例进入上下文。
 * 2. **只提供先验，不能单独提升置信等级**。这一步无法在工具内强制（工具不知道置信等级），
 *    因此约束写进 `newGaps`，由调查循环和仲裁节点兜底——
 *    [com.feedback.agent.rca.domain.HypothesisConfidence.HISTORY_ONLY_CEILING]。
 * 3. **必要现场证据缺失时，历史案例不能用来凑结论**。失败复盘二描述的就是：
 *    日志超时后 Agent 拿相似历史案例给出高置信候选。
 *
 * 所以本工具在 Observation 里显式声明自己「是弱证据」，这是刻意让模型看到的一句话。
 */
class HistoricalCaseTool(
    private val port: HistoricalCasePort,
    private val clock: RcaClock = RcaClock.system()
) : ReadOnlyRcaTool() {

    override val tool: RcaToolName = RcaToolName.HISTORICAL_CASE
    override val toolVersion: String = TOOL_VERSION

    override fun query(request: ToolRequest): ToolQueryOutcome {
        val signatureHint = requiredString(request, PARAM_SIGNATURE_HINT)
        val component = optionalString(request, PARAM_COMPONENT)
        val topK = optionalString(request, PARAM_TOP_K)?.toIntOrNull()?.coerceIn(1, MAX_TOP_K) ?: DEFAULT_TOP_K

        val envelope = port.fetch(
            HistoricalCaseQuery(signatureHint = signatureHint, component = component, topK = topK)
        )
        envelope.upstreamErrorCode?.let { return translateUpstreamError(it) }

        val record = envelope.payload
            ?: return ToolQueryOutcome(
                status = ToolResultStatus.UNKNOWN,
                observation = null,
                failureDetail = "历史案例上游既未返回错误码也未返回数据，按 UNKNOWN 处理",
                providerRequestId = envelope.providerRequestId
            )

        if (record.rows.isEmpty()) {
            return ToolQueryOutcome(
                status = ToolResultStatus.NO_VERIFIED_CASE,
                observation = null,
                failureDetail = "没有与 $signatureHint 匹配的历史案例；这不影响现场取证",
                providerRequestId = envelope.providerRequestId
            )
        }

        val unverified = record.rows.filterNot(HistoricalCaseRow::verificationPassed)
        if (!record.hasVerifiedCase) {
            return ToolQueryOutcome(
                status = ToolResultStatus.NO_VERIFIED_CASE,
                observation = null,
                failureDetail = "匹配到 ${record.rows.size} 条相似案例但均未经过发布后验证有效，不得作为先验使用",
                providerRequestId = envelope.providerRequestId
            )
        }

        val verified = record.rows.filter(HistoricalCaseRow::verificationPassed).sortedByDescending { it.confirmedAt }
        val gaps = buildList {
            if (unverified.isNotEmpty()) {
                add("${unverified.size} 条相似案例未经修复验证，已剔除")
            }
            add("历史案例只提供先验：必要现场证据缺失时不得据此提升置信等级，缺失必须输出 PARTIAL")
        }

        return ToolQueryOutcome(
            status = ToolResultStatus.SUCCESS_WITH_DATA,
            observation = ToolObservation(
                summary = buildString {
                    append("匹配到 ${verified.size} 条已验证案例：")
                    append(verified.joinToString("；") { "#${it.incidentId} ${it.title} → ${it.confirmedRootCause}" })
                },
                windowFrom = null,
                windowTo = null,
                timeWatermark = envelope.watermark,
                subjectRefs = verified.mapNotNull { it.fixVersion },
                newGaps = gaps,
                sourceRef = sourceRefOf(request)
            ),
            providerRequestId = envelope.providerRequestId
        )
    }

    /**
     * 回源指针按签名提示定位。
     *
     * 历史案例是**先验**而不是现场证据，所以 [EvidenceScopeMatcher] 对它一律判不覆盖：
     * 它可以把方向指得更准，但不能证明本次故障。主体标识也因此不作为组件候选。
     */
    override fun sourceRefOf(request: ToolRequest): String =
        "historical-case://${requiredString(request, PARAM_SIGNATURE_HINT)}"

    companion object {
        const val TOOL_VERSION: String = "1.0.0"

        const val PARAM_SIGNATURE_HINT: String = "signatureHint"
        const val PARAM_COMPONENT: String = "component"
        const val PARAM_TOP_K: String = "topK"

        const val DEFAULT_TOP_K: Int = 5
        const val MAX_TOP_K: Int = 20

        val EVIDENCE_TYPES: List<EvidenceType> = listOf(EvidenceType.HISTORICAL_CASE)
    }
}
