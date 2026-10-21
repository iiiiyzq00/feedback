package com.feedback.agent.rca.graph

import com.feedback.agent.rca.domain.HypothesisStatus
import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.tools.ToolInvocationGuard

/**
 * AGENT_INVESTIGATE：整张图里**唯一**由模型驱动的节点。
 *
 * 每轮循环固定是这六步，一步都不省：
 * `提出假设 → 选工具 → 读 Observation → 判断支持/反驳 → 修正假设 → 继续或停止`
 *
 * 「判断支持/反驳」不是让模型自由发挥：[com.feedback.agent.rca.tools.ReadOnlyRcaTool]
 * 已经内置了一条硬规则——只有取到数据才算支持、只有**合法空结果**才算反驳、
 * 超时和无权限只能记缺口。模型在这一步做的是在给定观察上修正假设，不是重新解释证据。
 *
 * 「继续或停止」同样不由模型定：模型提交 [com.feedback.agent.rca.domain.StopProposal]，
 * 由 [StopEvaluator] 四项检查裁决。本节点只负责把两者接起来，并在检查要求继续时再跑一轮。
 *
 * 循环上界有两重，取最先触发者：本次进入的剩余步数预算，以及预算/截止时间的硬闸门。
 * 两重上界是必要的——只靠模型自觉停止，会在「再查一次就好」的循环里把预算耗尽。
 */
class AgentInvestigateNode(
    private val model: InvestigationModel,
    private val guard: ToolInvocationGuard,
    private val updater: HypothesisUpdater = HypothesisUpdater(),
    private val stopEvaluator: StopEvaluator = StopEvaluator(),
    private val clock: RcaClock = RcaClock.system()
) : RcaNodeHandler {

    override val node: RcaNode = RcaNode.AGENT_INVESTIGATE

    override fun execute(context: RcaNodeContext): RcaNodeOutcome {
        var state = context.state
        var round = nextRound(state)
        var stopEvaluations = 0
        var roundsThisEntry = 0
        val maxRoundsThisEntry = state.gapsAndBudget.stepsRemaining

        while (true) {
            // 先把「当前时刻」刷新进状态，再判预算。
            // 状态里的 `now` 是任务进入时的时间戳，而墙钟上限约束的是**整个任务**：
            // 不刷新的话 `deadlineExceeded` 永远是 false，超时上限就成了摆设，
            // 一轮长调查会把预算表上的时间上限悄悄绕过去。
            // 这里刻意不递增 stateVersion：刷新时钟不是状态迁移，只是把同一份状态的
            // 时间字段对齐到当前时刻；递增会让落库版本与编排器持有的版本错位。
            state = state.copy(gapsAndBudget = state.gapsAndBudget.copy(now = clock.now()))

            // 硬闸门一：预算或截止时间。它优先于任何模型决策。
            state.gapsAndBudget.exhaustionStopReason()?.let { reason ->
                return denied(state, reason, "预算或截止时间在调查循环中耗尽，按停止原因降级")
            }
            // 硬闸门二：本次进入的轮次上限。
            if (roundsThisEntry >= maxRoundsThisEntry) {
                return denied(state, RcaStopReason.MAX_STEPS, "本次进入已达到步数上限 $maxRoundsThisEntry 轮，停止调查")
            }

            val decision = model.decide(buildPrompt(state, round))
            val proposal = decision.stop

            if (proposal != null) {
                stopEvaluations += 1
                val evaluation = stopEvaluator.evaluate(proposal, state, stopEvaluations)
                state = state.copy(stopProposal = proposal, stateVersion = state.stateVersion + 1)
                if (evaluation.decision == StopDecision.STOP) {
                    return stopAccepted(state, round, evaluation)
                }
                // 检查要求继续：把「为什么不让停」写进缺口，让下一轮的模型看得到。
                state = state.copy(
                    gapsAndBudget = state.gapsAndBudget.copy(
                        knownGaps = (state.gapsAndBudget.knownGaps + evaluation.explanation).distinct()
                    )
                )
                round += 1
                roundsThisEntry += 1
                continue
            }

            val action = decision.investigate
                ?: return denied(state, RcaStopReason.NO_VIABLE_TOOL, "模型既未提交调查动作也未提交停止提案")

            // 把「本步在检验哪个假设」并入参数，使它进入参数摘要：
            // 同一工具为不同假设发起的调用因此不会被去重合并——它们不是同一次取证。
            val request = action.toolRequest.copy(
                params = action.toolRequest.params + (ToolRequest.PARAM_TESTS_HYPOTHESIS to action.testsHypothesis),
                decisionRound = round
            )

            // 链路标识原样透传给工具边界（只为落库，不参与任何判定），见 RcaNodeContext.traceId。
            val outcome = guard.invoke(request, state, context.traceId)
            state = updater.apply(state, decision, outcome)

            if (outcome.rejected) {
                return denied(
                    state,
                    outcome.rejectedReason ?: RcaStopReason.BUDGET_EXHAUSTED,
                    "第 $round 轮调用被拒绝：${outcome.explanation}"
                )
            }
            round += 1
            roundsThisEntry += 1
        }
    }

    private fun nextRound(state: RcaSharedState): Int =
        state.toolHistory.maxOfOrNull { it.decisionRound }?.plus(1) ?: 1

    private fun stopAccepted(state: RcaSharedState, round: Int, evaluation: StopEvaluation): RcaNodeOutcome =
        RcaNodeOutcome(
            state = state,
            next = if (evaluation.status == RcaTaskStatus.COMPLETED) RcaNode.ROOT_CAUSE_ARBITRATE else null,
            enterReason = "进入动态调查循环，第 $round 轮模型提交停止提案",
            leaveReason = evaluation.explanation,
            terminalStatus = if (evaluation.status == RcaTaskStatus.COMPLETED) null else evaluation.status,
            stopReason = evaluation.stopReason
        )

    /**
     * 因预算、步数或非法决策而终止。
     *
     * 降级方向的选择依据是「现场证据拿不到」还是「证据覆盖不足」：
     * 预算/步数耗尽属于后者，输出 PARTIAL 保留已有证据；
     * 工具不可用或决策非法属于前者，转人工。
     */
    private fun denied(state: RcaSharedState, reason: RcaStopReason, explanation: String): RcaNodeOutcome =
        RcaNodeOutcome(
            state = state,
            next = null,
            enterReason = "进入动态调查循环",
            leaveReason = explanation,
            terminalStatus = when (reason) {
                RcaStopReason.MAX_STEPS,
                RcaStopReason.BUDGET_EXHAUSTED,
                RcaStopReason.DEADLINE_EXCEEDED,
                RcaStopReason.LOW_INFORMATION_GAIN -> RcaTaskStatus.PARTIAL

                else -> RcaTaskStatus.NEEDS_HUMAN
            },
            stopReason = reason
        )

    /**
     * 组装交给模型的上下文。
     *
     * 三个刻意的取舍，都可以在面试里展开：
     * - 只给证据摘要和 evidence ID，不给原文——上下文体积因此与证据条数解耦。
     * - 只给「已尝试过的工具 + 参数摘要」，不给完整调用历史——让模型避免重复取证，
     *   但不需要把过去的每次调用读一遍。
     * - 未完成的强制证据项原样带上——模型的第一优先级是补齐门禁项，而不是自由探索。
     */
    private fun buildPrompt(state: RcaSharedState, round: Int): InvestigationPrompt = InvestigationPrompt(
        problemProfile = state.problemProfile,
        mandatoryPlan = state.mandatoryPlan,
        hypotheses = state.hypotheses.filter { it.status != HypothesisStatus.DROPPED },
        evidenceBriefs = state.evidenceLedger.map(EvidenceBrief::of),
        gaps = state.gapsAndBudget.knownGaps,
        budget = state.gapsAndBudget,
        round = round,
        attemptedReuseKeys = state.toolHistory.map { "${it.toolName}@${it.paramsDigest}" }
    )
}
