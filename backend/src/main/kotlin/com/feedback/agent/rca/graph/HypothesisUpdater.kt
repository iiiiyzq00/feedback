package com.feedback.agent.rca.graph

import com.feedback.agent.rca.domain.GapsAndBudget
import com.feedback.agent.rca.domain.Hypothesis
import com.feedback.agent.rca.domain.HypothesisConfidence
import com.feedback.agent.rca.domain.HypothesisStatus
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.ToolCallRecord
import com.feedback.agent.rca.tools.GuardedToolOutcome
import java.math.BigDecimal

/**
 * 假设与证据的更新规则。
 *
 * 单独成类的理由：这一组规则是「模型提假设、程序管推理」的交界处，必须能被逐条复算和单测。
 * 如果把它散在调查循环里，规则会随迭代慢慢变成「模型说什么就是什么」。
 *
 * 三条硬规则：
 *
 * 1. **反证不删除假设**。被反驳的假设保留记录并标 [HypothesisStatus.REFUTED]，
 *    因为「我们排除掉了纯客户端解码」这件事本身就是结论文档的一部分。
 * 2. **置信等级由证据类型推导，不由模型自述**。模型提交的 [AgentDecision.hypothesisUpdates]
 *    里没有置信度字段，就是这个原因——等级必须可复算。
 * 3. **历史案例单独支撑时封顶**。仅由历史案例支撑的假设最高只能到
 *    [HypothesisConfidence.HISTORY_ONLY_CEILING]，这是失败复盘二的直接产物。
 */
class HypothesisUpdater {

    /**
     * 应用一次决策与它的工具结果，产出新的共享状态。
     *
     * 不可变更新：返回新的 [RcaSharedState]，[RcaSharedState.stateVersion] 递增。
     * 调用方拿它去写库，写回条件是 `state_version` 匹配，因此旧 Worker 的快照会被拒绝。
     */
    fun apply(state: RcaSharedState, decision: AgentDecision, outcome: GuardedToolOutcome?): RcaSharedState {
        val withHypotheses = applyHypothesisUpdates(state, decision)
        val withOutcome = outcome?.let { mergeOutcome(withHypotheses, decision, it) } ?: withHypotheses
        val consumed = consumeBudget(withOutcome, decision, outcome)
        return consumed.copy(stateVersion = state.stateVersion + 1)
    }

    // -----------------------------------------------------------------------
    // 决策中的假设变动
    // -----------------------------------------------------------------------

    private fun applyHypothesisUpdates(state: RcaSharedState, decision: AgentDecision): RcaSharedState {
        var hypotheses = state.hypotheses
        decision.hypothesisUpdates.forEach { update ->
            val existing = hypotheses.firstOrNull { it.code == update.code }
            hypotheses = when (update.action) {
                HypothesisUpdateAction.PROPOSE ->
                    if (existing == null) {
                        hypotheses + Hypothesis(
                            code = update.code,
                            statement = update.statement,
                            mechanismNote = update.mechanismNote,
                            confidence = HypothesisConfidence.CANDIDATE
                        )
                    } else {
                        // 重复提出同一编码：视为修正，不新增记录，避免假设数虚高。
                        hypotheses.replace(existing, revise(existing, update))
                    }

                HypothesisUpdateAction.REVISE ->
                    if (existing == null) hypotheses else hypotheses.replace(existing, revise(existing, update))

                HypothesisUpdateAction.MERGE -> {
                    val target = update.mergedIntoCode
                    if (existing == null || target == null) {
                        hypotheses
                    } else {
                        hypotheses.replace(
                            existing,
                            existing.copy(
                                status = HypothesisStatus.MERGED,
                                mergedIntoCode = target,
                                revision = existing.revision + 1
                            )
                        )
                    }
                }

                HypothesisUpdateAction.DROP ->
                    if (existing == null) {
                        hypotheses
                    } else {
                        hypotheses.replace(
                            existing,
                            existing.copy(
                                status = HypothesisStatus.DROPPED,
                                revision = existing.revision + 1
                            )
                        )
                    }
            }
        }
        return state.copy(hypotheses = hypotheses)
    }

    private fun revise(existing: Hypothesis, update: HypothesisUpdate): Hypothesis = existing.copy(
        statement = update.statement.ifBlank { existing.statement },
        mechanismNote = update.mechanismNote ?: existing.mechanismNote,
        revision = existing.revision + 1
    )

    // -----------------------------------------------------------------------
    // 工具结果的合并
    // -----------------------------------------------------------------------

    private fun mergeOutcome(
        state: RcaSharedState,
        decision: AgentDecision,
        outcome: GuardedToolOutcome
    ): RcaSharedState {
        var next = state

        outcome.record?.let { record -> next = appendToolHistory(next, record) }
        outcome.evidence?.let { evidence ->
            // 同一 evidence ID 只入账一次：证据 ID 是确定性的，重复入账会让证据数虚高。
            if (next.evidenceLedger.none { it.evidenceId == evidence.evidenceId }) {
                next = next.copy(evidenceLedger = next.evidenceLedger + evidence)
            }
        }

        val observation = outcome.result?.observation
        if (observation != null) {
            val evidenceId = outcome.evidence?.evidenceId
            observation.supportsHypotheses.forEach { code ->
                next = markSupported(next, code, evidenceId)
            }
            observation.refutesHypotheses.forEach { code ->
                next = markRefuted(next, code, evidenceId)
            }
            if (observation.newGaps.isNotEmpty()) {
                next = next.copy(
                    gapsAndBudget = next.gapsAndBudget.copy(
                        knownGaps = (next.gapsAndBudget.knownGaps + observation.newGaps).distinct()
                    )
                )
            }
        }

        // 被拒绝的调用也要记缺口：预算拒绝不是「没查」，而是「没查成」，必须让后续判定看得到。
        if (outcome.rejected && outcome.rejectedReason != null) {
            next = next.copy(
                gapsAndBudget = next.gapsAndBudget.copy(
                    knownGaps = (next.gapsAndBudget.knownGaps +
                        "第 ${decision.investigate?.toolRequest?.decisionRound ?: 0} 轮调用被拒绝：" +
                        "${outcome.rejectedReason.storageCode}")
                        .distinct()
                )
            )
        }

        return next
    }

    private fun appendToolHistory(state: RcaSharedState, record: ToolCallRecord): RcaSharedState =
        state.copy(toolHistory = state.toolHistory + record)

    /**
     * 标记假设被支持，并按证据构成重算置信等级。
     *
     * 等级推导规则（确定性、可复算）：
     * - 无支撑证据 → `CANDIDATE`
     * - 1 条支撑 → `WEAK`
     * - ≥2 条支撑 → `MODERATE`
     * - 有 cohort 级证据且 ≥2 条支撑 → `STRONG`
     * - 仅由历史案例支撑 → 封顶 `MODERATE`
     *
     * 刻意不设 `CONFIRMED`：根因确认是工程师的动作，不是程序的输出。
     */
    private fun markSupported(state: RcaSharedState, code: String, evidenceId: String?): RcaSharedState {
        val hypothesis = state.hypotheses.firstOrNull { it.code == code } ?: return state
        val supporting = evidenceId?.let { id ->
            (hypothesis.supportingEvidenceIds + id).distinct()
        } ?: hypothesis.supportingEvidenceIds

        val withEvidence = hypothesis.copy(
            supportingEvidenceIds = supporting,
            status = if (hypothesis.status == HypothesisStatus.REFUTED) {
                hypothesis.status
            } else {
                HypothesisStatus.SUPPORTED
            },
            revision = hypothesis.revision + 1
        )
        val upgraded = withEvidence.copy(confidence = deriveConfidence(state, withEvidence))
        return state.copy(hypotheses = state.hypotheses.replace(hypothesis, upgraded))
    }

    /**
     * 标记假设被反驳。
     *
     * 注意这里区分两种情况：
     * - 假设本身被直接反驳：标 `REFUTED`，从候选集中排除，但记录保留。
     *   414 回放里的「客户端解码缺陷」走的就是这一条：崩溃平台对该应用没有任何记录，
     *   这是一条合法空结果构成的反证。记录保留而不是删除，是因为
     *   「我们排除掉了纯客户端缺陷」本身就是结论文档的一部分。
     * - 假设**已被支持**、又出现新的反证：不直接推翻，而是累加 `unresolvedRefutationCount`，
     *   由仲裁节点要求机制链解释它。这是「未处理的强反证禁止正常停止」的数据来源：
     *   一个已经被证据支持的假设不能因为出现一条反证就被丢弃，但也不能当作没看见。
     */
    private fun markRefuted(state: RcaSharedState, code: String, evidenceId: String?): RcaSharedState {
        val hypothesis = state.hypotheses.firstOrNull { it.code == code } ?: return state
        val refuting = evidenceId?.let { id -> (hypothesis.refutingEvidenceIds + id).distinct() }
            ?: hypothesis.refutingEvidenceIds

        val updated = if (hypothesis.supportingEvidenceIds.isEmpty()) {
            hypothesis.copy(
                refutingEvidenceIds = refuting,
                status = HypothesisStatus.REFUTED,
                confidence = HypothesisConfidence.REFUTED,
                revision = hypothesis.revision + 1
            )
        } else {
            hypothesis.copy(
                refutingEvidenceIds = refuting,
                unresolvedRefutationCount = hypothesis.unresolvedRefutationCount + 1,
                revision = hypothesis.revision + 1
            )
        }
        return state.copy(hypotheses = state.hypotheses.replace(hypothesis, updated))
    }

    /** 依据证据构成重算置信等级。历史案例封顶规则在这里生效。 */
    private fun deriveConfidence(state: RcaSharedState, hypothesis: Hypothesis): HypothesisConfidence {
        val evidence = hypothesis.supportingEvidenceIds.mapNotNull { id ->
            state.evidenceLedger.firstOrNull { it.evidenceId == id }
        }
        if (evidence.isEmpty()) return HypothesisConfidence.CANDIDATE

        val hasCohort = evidence.any { it.usableForRangeClaim }
        val nonHistoryCount = evidence.count { it.type.storageCode != HISTORY_EVIDENCE_TYPE }
        val derived = when {
            hasCohort && nonHistoryCount >= 2 -> HypothesisConfidence.STRONG
            evidence.size >= 2 -> HypothesisConfidence.MODERATE
            else -> HypothesisConfidence.WEAK
        }
        // 历史案例只能提供先验：仅由历史案例支撑时封顶，且不得进入 STRONG。
        val historyOnly = evidence.all { it.type.storageCode == HISTORY_EVIDENCE_TYPE }
        return if (historyOnly) minOf(derived, HypothesisConfidence.HISTORY_ONLY_CEILING) else derived
    }

    // -----------------------------------------------------------------------
    // 预算记账
    // -----------------------------------------------------------------------

    /**
     * 累加本轮消耗。重放与拒绝的调用不消耗步数，但模型推理本身的 token 照常计入预算。
     */
    private fun consumeBudget(
        state: RcaSharedState,
        decision: AgentDecision,
        outcome: GuardedToolOutcome?
    ): RcaSharedState {
        val stepsUsed = if (outcome?.consumedBudget == true) 1 else 0
        val toolCost = if (outcome?.consumedBudget == true) {
            outcome.result?.costUsd ?: BigDecimal.ZERO
        } else {
            BigDecimal.ZERO
        }
        val budget: GapsAndBudget = state.gapsAndBudget.copy(
            stepsUsed = state.gapsAndBudget.stepsUsed + stepsUsed,
            tokensUsed = state.gapsAndBudget.tokensUsed + decision.tokenCost,
            costUsedUsd = state.gapsAndBudget.costUsedUsd + decision.costUsd + toolCost
        )
        return state.copy(gapsAndBudget = budget)
    }

    private companion object {
        /** 历史案例的证据类型编码；用于封顶判定。 */
        const val HISTORY_EVIDENCE_TYPE = "HISTORICAL_CASE"

        fun List<Hypothesis>.replace(old: Hypothesis, new: Hypothesis): List<Hypothesis> =
            map { if (it.code == old.code && it.revision == old.revision) new else it }
    }
}
