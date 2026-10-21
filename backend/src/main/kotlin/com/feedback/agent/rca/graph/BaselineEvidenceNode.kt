package com.feedback.agent.rca.graph

import com.feedback.agent.rca.domain.EvidenceCoverage
import com.feedback.agent.rca.domain.EvidenceEntry
import com.feedback.agent.rca.domain.EvidenceType
import com.feedback.agent.rca.domain.MandatoryCompletion
import com.feedback.agent.rca.domain.MandatoryEvidencePlan
import com.feedback.agent.rca.domain.MandatoryRequirement
import com.feedback.agent.rca.domain.MandatoryRequirementType
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaToolName
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.domain.ToolResultStatus
import com.feedback.agent.rca.tools.ClusteredSignaturePort
import com.feedback.agent.rca.tools.EvidenceSourceRef
import com.feedback.agent.rca.tools.GuardedToolOutcome
import com.feedback.agent.rca.tools.RcaCanonicalDigest
import com.feedback.agent.rca.tools.ToolInvocationGuard
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * BASELINE_EVIDENCE：纯确定性节点，逐项执行强制证据计划。
 *
 * 这是「必要证据 Agent 不能跳过」这句设计口号的实现处。三个要点：
 *
 * 1. **执行顺序由计划决定，不由模型决定**。有合法日志 ID 就必查日志、Crash/ANR 就必取堆栈、
 *    多成员簇就先看签名分布——这三条写在 [MandatoryEvidencePlan.from] 里，
 *    本节点只是照着做。模型在这一步还没有说话的机会。
 * 2. **成功但为空 与 查询失败 必须区分**。`SUCCESS_EMPTY` / `NOT_FOUND` 记
 *    [MandatoryCompletion.SATISFIED]——查询确实做了，没有就是没有；而超时、无权限、
 *    样本不足记 [MandatoryCompletion.FAILED_GAP] 并保留失败语义。
 *    把后者当成前者，就是失败复盘二的起点。
 * 3. **任一强制项失败不中止流程**。剩余项继续执行——多拿到一项证据，人工接手时就少补一项。
 *    是否转人工由停止判定统一裁决，而不是在这里提前放弃。
 *
 * 签名分布项是唯一的例外：它不走上游工具，而是读问题组织层已固化的簇内签名分布。
 * 理由是这个分布本来就属于「问题组织」的产出，包装成一个 Agent 工具会让
 * 「Agent 只能通过只读工具取证」这条边界失去意义。
 */
class BaselineEvidenceNode(
    private val guard: ToolInvocationGuard,
    private val signaturePort: ClusteredSignaturePort,
    private val baselineRound: Int = BASELINE_ROUND
) : RcaNodeHandler {

    override val node: RcaNode = RcaNode.BASELINE_EVIDENCE

    override fun execute(context: RcaNodeContext): RcaNodeOutcome {
        var state = context.state
        val pending = state.mandatoryPlan.requirements.filter { it.completion == MandatoryCompletion.PENDING }

        pending.forEach { requirement ->
            state = if (requirement.type == MandatoryRequirementType.ERROR_SIGNATURE_DISTRIBUTION) {
                executeSignatureDistribution(state, requirement)
            } else {
                executeToolRequirement(state, requirement, context.traceId)
            }
        }

        val blocking = state.mandatoryPlan.blocking
        return RcaNodeOutcome(
            state = state,
            // 强制证据未齐也继续进入调查循环：模型可以在那里补齐，或由仲裁节点显式豁免。
            next = RcaNode.AGENT_INVESTIGATE,
            enterReason = "上下文构建完成后执行强制证据计划，共 ${context.state.mandatoryPlan.requirements.size} 项",
            leaveReason = if (blocking.isEmpty()) {
                "强制证据计划全部完成或已豁免"
            } else {
                "仍有 ${blocking.size} 项未完成：" +
                    "${blocking.joinToString { "${it.code}(${it.completion.storageCode})" }}，" +
                    "交由调查循环与仲裁处理"
            }
        )
    }

    // -----------------------------------------------------------------------
    // 走上游工具的三类强制项
    // -----------------------------------------------------------------------

    private fun executeToolRequirement(
        state: RcaSharedState,
        requirement: MandatoryRequirement,
        traceId: String?
    ): RcaSharedState {
        val request = buildToolRequest(requirement, state)
            ?: return failRequirement(
                state,
                requirement.code,
                ToolResultStatus.UNKNOWN,
                "无法为 ${requirement.code} 构造合法调用参数，按证据缺口处理而不是跳过"
            )

        // 链路标识原样透传给工具边界（只为落库，不参与任何判定），见 RcaNodeContext.traceId。
        val outcome = guard.invoke(request, state, traceId)
        if (outcome.rejected) {
            return failRequirement(
                state,
                requirement.code,
                ToolResultStatus.UNKNOWN,
                "调用被拒绝（${outcome.rejectedReason?.storageCode}）：${outcome.explanation}"
            )
        }
        return applyResult(
            mergeOutcome(state, outcome),
            requirement.code,
            outcome.result?.status,
            outcome.evidence?.evidenceId
        )
    }

    /**
     * 按强制项类型构造调用请求。返回 null 表示事实不足以构造合法请求——
     * 例如「计划里要求查日志但画像里没有日志 ID」，这属于事实层不一致，必须记缺口而不是猜一个 ID。
     *
     * 时间窗取问题画像的 `[occurredFrom, occurredTo)` 并向两侧放宽 [WINDOW_PADDING]：
     * 现象上报时间通常晚于实际失败时间，贴着现象边界查会漏掉首错点。
     */
    private fun buildToolRequest(requirement: MandatoryRequirement, state: RcaSharedState): ToolRequest? {
        val profile = state.problemProfile
        val from = profile.occurredFrom.minus(WINDOW_PADDING).format(TIME_FORMAT)
        val to = profile.occurredTo.plus(WINDOW_PADDING).format(TIME_FORMAT)

        return when (requirement.type) {
            MandatoryRequirementType.LOG_TRACE -> {
                val logId = profile.logId ?: return null
                ToolRequest(
                    tool = RcaToolName.LOG_TRACE,
                    params = mapOf("logId" to logId, "windowFrom" to from, "windowTo" to to),
                    decisionRound = baselineRound,
                    selectionReason = "强制证据：存在合法日志 ID，必须先取首错点",
                    paramsDigest = ""
                )
            }

            MandatoryRequirementType.CRASH_STACK -> ToolRequest(
                tool = RcaToolName.CRASH_ANR,
                params = mapOf(
                    "appId" to profile.appId,
                    "version" to profile.affectedVersions.firstOrNull(),
                    "windowFrom" to from,
                    "windowTo" to to
                ),
                decisionRound = baselineRound,
                selectionReason = "强制证据：现象包含 Crash/ANR，必须先取堆栈",
                paramsDigest = ""
            )

            MandatoryRequirementType.COHORT_COMPARISON -> {
                // 对照组版本缺失时不给默认值：默认值会让集中度结论拿一个不存在的版本做对照。
                val control = profile.controlVersion ?: return null
                ToolRequest(
                    tool = RcaToolName.VERSION_COHORT,
                    params = mapOf(
                        "appId" to profile.appId,
                        "metricKey" to COHORT_METRIC_KEY,
                        "controlVersion" to control,
                        "windowFrom" to from,
                        "windowTo" to to
                    ),
                    decisionRound = baselineRound,
                    selectionReason = "强制证据：影响面跨版本，必须给出分母与对照组",
                    paramsDigest = ""
                )
            }

            // 签名分布不走工具，在 executeSignatureDistribution 里单独处理。
            MandatoryRequirementType.ERROR_SIGNATURE_DISTRIBUTION -> null
        }
    }

    // -----------------------------------------------------------------------
    // 签名分布：读问题组织层的产出
    // -----------------------------------------------------------------------

    /**
     * 读簇内已固化的错误签名分布。
     *
     * 证据覆盖类型是 [EvidenceCoverage.CLUSTER]——它覆盖的是**一个联合调查候选内的分布**，
     * 比单点证据强、比带对照组的 cohort 证据弱。仲裁校验范围性 claim 时依赖这个区分。
     */
    private fun executeSignatureDistribution(state: RcaSharedState, requirement: MandatoryRequirement): RcaSharedState {
        val clusterId = state.problemProfile.clusterId
            ?: return failRequirement(
                state,
                requirement.code,
                ToolResultStatus.UNKNOWN,
                "问题画像没有联合调查候选 ID，无法读取签名分布"
            )

        val distribution = signaturePort.load(clusterId)
        if (distribution.isEmpty()) {
            return failRequirement(
                state,
                requirement.code,
                ToolResultStatus.NOT_FOUND,
                "联合调查候选 $clusterId 没有已固化的签名分布"
            )
        }

        val digest = RcaCanonicalDigest.sha256(
            distribution.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }
        )
        val evidence = EvidenceEntry(
            evidenceId = "EV-SIGNATURE-DISTRIBUTION-${digest.take(16)}",
            type = EvidenceType.ERROR_SIGNATURE_DISTRIBUTION,
            sourceTool = EvidenceSourceRef.SIGNATURE_SOURCE,
            sourceRef = "cluster-signature://$clusterId",
            coverageType = EvidenceCoverage.CLUSTER,
            coverageScope = "cluster:$clusterId",
            numerator = distribution.values.sum(),
            denominator = distribution.values.sum(),
            contentDigest = digest,
            summary = "簇内 ${distribution.size} 个错误签名，共 ${distribution.values.sum()} 条：" +
                distribution.entries.sortedByDescending { it.value }
                    .joinToString("；") { "${it.key}×${it.value}" }
        )

        val updated = state.mandatoryPlan.requirements.map { item ->
            if (item.code == requirement.code) {
                item.copy(completion = MandatoryCompletion.SATISFIED, satisfiedByEvidenceId = evidence.evidenceId)
            } else {
                item
            }
        }
        return state.copy(
            mandatoryPlan = MandatoryEvidencePlan(updated),
            evidenceLedger = state.evidenceLedger + evidence,
            stateVersion = state.stateVersion + 1
        )
    }

    // -----------------------------------------------------------------------
    // 结果合并与强制项状态更新
    // -----------------------------------------------------------------------

    private fun mergeOutcome(state: RcaSharedState, outcome: GuardedToolOutcome): RcaSharedState {
        var next = state
        outcome.record?.let { record -> next = next.copy(toolHistory = next.toolHistory + record) }
        outcome.evidence?.let { evidence ->
            // 证据 ID 是确定性的，同一 ID 只入账一次，否则证据数会虚高。
            if (next.evidenceLedger.none { it.evidenceId == evidence.evidenceId }) {
                next = next.copy(evidenceLedger = next.evidenceLedger + evidence)
            }
        }
        outcome.result?.observation?.newGaps?.takeIf(List<String>::isNotEmpty)?.let { gaps ->
            next = next.copy(
                gapsAndBudget = next.gapsAndBudget.copy(knownGaps = (next.gapsAndBudget.knownGaps + gaps).distinct())
            )
        }
        return next
    }

    /**
     * 依据工具结果更新强制项状态。判定表是本节点最核心的一处逻辑：
     *
     * - 有数据 → `SATISFIED`，并记下满足它的 evidence ID。
     * - 合法空结果 / NOT_FOUND → 也记 `SATISFIED`。查询确实做了，没有就是没有，
     *   这是**可用结论**而不是缺口。
     * - 其余（超时、无权限、样本不足、符号缺失、未知）→ `FAILED_GAP` 并保留失败语义。
     */
    private fun applyResult(
        state: RcaSharedState,
        code: String,
        status: ToolResultStatus?,
        evidenceId: String?
    ): RcaSharedState {
        if (status == null) {
            return failRequirement(state, code, ToolResultStatus.UNKNOWN, "调用未产生结果状态")
        }
        val satisfied =
            status.hasData || status == ToolResultStatus.SUCCESS_EMPTY || status == ToolResultStatus.NOT_FOUND
        val updated = state.mandatoryPlan.requirements.map { requirement ->
            when {
                requirement.code != code -> requirement
                satisfied -> requirement.copy(
                    completion = MandatoryCompletion.SATISFIED,
                    satisfiedByEvidenceId = evidenceId,
                    failureSemantic = null
                )

                else -> requirement.copy(completion = MandatoryCompletion.FAILED_GAP, failureSemantic = status)
            }
        }
        return state.copy(mandatoryPlan = MandatoryEvidencePlan(updated), stateVersion = state.stateVersion + 1)
    }

    private fun failRequirement(
        state: RcaSharedState,
        code: String,
        status: ToolResultStatus,
        reason: String
    ): RcaSharedState {
        val updated = state.mandatoryPlan.requirements.map { requirement ->
            if (requirement.code == code) {
                requirement.copy(completion = MandatoryCompletion.FAILED_GAP, failureSemantic = status)
            } else {
                requirement
            }
        }
        return state.copy(
            mandatoryPlan = MandatoryEvidencePlan(updated),
            gapsAndBudget = state.gapsAndBudget.copy(knownGaps = (state.gapsAndBudget.knownGaps + reason).distinct()),
            stateVersion = state.stateVersion + 1
        )
    }

    private companion object {
        /** 强制证据调用的轮次基准；与模型决策轮次错开，便于在工具历史里区分来源。 */
        const val BASELINE_ROUND = 0

        /** 时间窗向两侧放宽的幅度：现象上报时间通常晚于实际失败时间。 */
        val WINDOW_PADDING: Duration = Duration.ofMinutes(30)

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

        /** 版本对照使用的指标键；与问题画像的故障类型对应。 */
        const val COHORT_METRIC_KEY = "video_play_failure_rate"
    }
}
