package com.feedback.agent.rca.graph

import com.feedback.agent.rca.domain.Hypothesis
import com.feedback.agent.rca.domain.HypothesisConfidence
import com.feedback.agent.rca.domain.HypothesisStatus
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus
import com.feedback.agent.rca.domain.StopProposal

/**
 * 编排器的停止四项检查。
 *
 * 模型提交的是**提案**（[StopProposal]），能否停由这里判定。这一分层是整套设计的枢纽：
 * 如果把停止判断交给模型，模型会在证据不足时给出一个看起来完整的结论，
 * 而「不让模型硬凑答案」正是这套系统存在的理由。
 *
 * 四项检查缺一不可：
 * 1. 强制证据计划完成（未完成且未豁免即拒绝停止）；
 * 2. 最低证据门槛（至少一条可用证据，且必须覆盖被查询的对象）；
 * 3. 未处理的强反证（大于 0 即拒绝正常停止）；
 * 4. 预算状态（由共享状态本身决定，提案无权覆盖）。
 *
 * 检查结论只有三种去向：继续调查、[RcaTaskStatus.PARTIAL]、[RcaTaskStatus.NEEDS_HUMAN]。
 * 注意 PARTIAL 与 NEEDS_HUMAN 的区别——前者是「有证据但覆盖不足」，后者是
 * 「必要现场证据拿不到」，两者的后续人工动作完全不同，不能合并成一个「失败」。
 */
class StopEvaluator {

    /**
     * 判定停止提案。
     *
     * @param proposal 模型提交的结构化提案。
     * @param state 当前共享状态。
     * @param arbitrateRevisionCount 仲裁节点已经回退过几次；超过限额不再允许继续。
     */
    fun evaluate(
        proposal: StopProposal,
        state: RcaSharedState,
        arbitrateRevisionCount: Int
    ): StopEvaluation {
        // 第 4 项：预算。预算耗尽是硬约束，提案说「证据够了」也不能覆盖。
        state.gapsAndBudget.exhaustionStopReason()?.let { exhausted ->
            return StopEvaluation(
                decision = StopDecision.STOP,
                status = resolveDegradedStatus(state, exhausted),
                stopReason = exhausted,
                explanation = "预算或截止时间耗尽（${exhausted.storageCode}），" +
                    "按 ${resolveDegradedStatus(state, exhausted).storageCode} 降级"
            )
        }

        // 第 1 项：强制证据计划。未完成且未豁免即拒绝停止。
        val blocking = state.mandatoryPlan.blocking
        if (blocking.isNotEmpty()) {
            return StopEvaluation(
                decision = StopDecision.CONTINUE,
                status = null,
                stopReason = null,
                explanation = "强制证据计划仍有 ${blocking.size} 项未完成：${blocking.joinToString { it.code }}"
            )
        }

        // 第 3 项：未处理的强反证。
        if (state.unresolvedRefutationCount > 0) {
            val canRetry = arbitrateRevisionCount < MAX_STOP_EVALUATIONS
            return if (canRetry) {
                StopEvaluation(
                    decision = StopDecision.CONTINUE,
                    status = null,
                    stopReason = null,
                    explanation = "仍有 ${state.unresolvedRefutationCount} 条强反证未处理，" +
                        "需要继续取证或明确记录无法排除"
                )
            } else {
                StopEvaluation(
                    decision = StopDecision.STOP,
                    status = RcaTaskStatus.NEEDS_HUMAN,
                    stopReason = RcaStopReason.UNRESOLVED_REFUTATION,
                    explanation = "已尝试 $arbitrateRevisionCount 轮仍未排除 " +
                        "${state.unresolvedRefutationCount} 条强反证，转人工裁决"
                )
            }
        }

        // 第 2 项：最低证据门槛。
        val usableEvidence = state.evidenceLedger.filter { it.contentDigest.isNotBlank() }
        val supportedHypotheses = state.hypotheses.filter { it.status == HypothesisStatus.SUPPORTED }
        if (usableEvidence.isEmpty() || supportedHypotheses.isEmpty()) {
            return StopEvaluation(
                decision = StopDecision.STOP,
                status = RcaTaskStatus.PARTIAL,
                stopReason = RcaStopReason.LOW_INFORMATION_GAIN,
                explanation = "可用证据 ${usableEvidence.size} 条、受支持假设 ${supportedHypotheses.size} 个，" +
                    "未达到最低证据门槛，按 PARTIAL 输出候选而不给结论"
            )
        }

        return StopEvaluation(
            decision = StopDecision.STOP,
            status = RcaTaskStatus.COMPLETED,
            stopReason = proposal.reason,
            explanation = "四项检查全部通过：强制证据齐备、反证已处理、证据门槛达标、预算充足"
        )
    }

    /**
     * 依据状态自身推导降级形态。
     *
     * 判据是「必要现场证据是否拿得到」：只要有强制证据项以缺口结束，
     * 就属于 [RcaTaskStatus.NEEDS_HUMAN]（人工去补现场证据）；
     * 若强制证据都拿到了、只是覆盖不足，则属于 [RcaTaskStatus.PARTIAL]。
     *
     * 这个区分直接对应失败复盘二：日志拿不到时应当转人工，而不是用历史案例凑结论。
     */
    private fun resolveDegradedStatus(state: RcaSharedState, reason: RcaStopReason): RcaTaskStatus = when {
        reason == RcaStopReason.CANCELLED -> RcaTaskStatus.CANCELLED

        state.mandatoryPlan.requirements.any {
            it.completion == com.feedback.agent.rca.domain.MandatoryCompletion.FAILED_GAP
        } -> RcaTaskStatus.NEEDS_HUMAN

        else -> RcaTaskStatus.PARTIAL
    }

    companion object {
        /** 停止判定最多重复评估的轮次；超过即降级，避免「再试一次就好」的活锁。 */
        const val MAX_STOP_EVALUATIONS: Int = 3

        /**
         * 最小证据门槛：至少一条可用证据。单独抽出来的原因是阈值会随试点数据调整，
         * 调整时必须改版本化配置而不是就地改数字。
         */
        const val MINIMUM_USABLE_EVIDENCE: Int = 1

        /** 仅凭历史案例可达到的最高置信等级，供仲裁与调查循环共同引用。 */
        val HISTORY_ONLY_CEILING: HypothesisConfidence = HypothesisConfidence.HISTORY_ONLY_CEILING

        /** 判断某个假设是否只由历史案例支撑。 */
        fun isHistoryOnly(hypothesis: Hypothesis, historyEvidenceIds: Set<String>): Boolean =
            hypothesis.supportingEvidenceIds.isNotEmpty() &&
                hypothesis.supportingEvidenceIds.all(historyEvidenceIds::contains)
    }
}

enum class StopDecision {
    /** 继续调查：回到 [com.feedback.agent.rca.domain.RcaNode.AGENT_INVESTIGATE] 再取一轮证据。 */
    CONTINUE,

    /** 接受停止：按 [StopEvaluation.status] 落终态。 */
    STOP
}

data class StopEvaluation(
    val decision: StopDecision,
    /** [StopDecision.STOP] 时必非空。 */
    val status: RcaTaskStatus?,
    /** [StopDecision.STOP] 时必非空，对应 V242 迁移的 `chk_rca_agent_stop_reason` 约束。 */
    val stopReason: RcaStopReason?,
    val explanation: String
) {
    init {
        require(decision == StopDecision.CONTINUE || (status != null && stopReason != null)) {
            "接受停止时必须给出终态与停止原因"
        }
    }
}
