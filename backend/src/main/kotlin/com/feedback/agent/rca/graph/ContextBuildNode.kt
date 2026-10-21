package com.feedback.agent.rca.graph

import com.feedback.agent.rca.domain.GapsAndBudget
import com.feedback.agent.rca.domain.ImpactCounts
import com.feedback.agent.rca.domain.MandatoryEvidencePlan
import com.feedback.agent.rca.domain.ProblemProfile
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaSharedState
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Episode 事实来源端口。
 *
 * 只读，且只返回**已经落库的事实**：本节点不回查上游、不调模型。
 * 这样做的理由是可复算——上下文构建完全由已固化的事实决定，同一批事实重复跑必然同一结果。
 */
interface EpisodeFactsPort {
    fun load(episodeId: Long): EpisodeFacts?
}

/**
 * 问题对象的事实快照。字段与 [ProblemProfile] 一一对应，但刻意分开：
 * 端口返回的是「事实」，[ProblemProfile] 是「交给模型的画像」，两者可能随需要分化。
 */
data class EpisodeFacts(
    val episodeId: Long,
    val tenantKey: String,
    val appId: String,
    val problemKey: String,
    val generation: Int,
    val errorSignature: String?,
    val occurredFrom: LocalDateTime,
    val occurredTo: LocalDateTime,
    val impact: ImpactCounts,
    val crashOrAnr: Boolean,
    val hasValidLogId: Boolean,
    /** 合法日志 ID 本身；[hasValidLogId] 为 true 时必须提供，否则事实层自相矛盾。 */
    val logId: String?,
    val clusterMemberCount: Int,
    val clusterId: Long?,
    val affectedVersions: List<String>,
    /** 版本对照的基准版本；为空表示事实不足，不能输出版本集中度结论。 */
    val controlVersion: String?,
    val symptomSummary: String
) {
    init {
        require(!hasValidLogId || logId != null) {
            "标记存在合法日志 ID 却没有给出日志 ID，事实层不一致"
        }
    }
}

/**
 * CONTEXT_BUILD：纯确定性节点，把 Episode 事实装配成问题画像与强制证据计划。
 *
 * 这个节点不调模型，是「外层确定性编排」的第一个落点。它产出两样东西：
 * - [ProblemProfile]：锚定事实，告诉后续节点和模型「你在查哪个问题对象」，
 *   防止跨 Episode 混证（那也是「代表日志只能证明被查询的那一个 Episode」的前提）。
 * - [MandatoryEvidencePlan]：门禁。由 [MandatoryEvidencePlan.from] 按确定性规则生成，
 *   模型无法增删，只能执行或由仲裁节点显式豁免。
 *
 * 事实缺失时不留空转：直接终止而不是继续跑。一个没有事实的 Episode 走完五个节点，
 * 产出的是看起来完整的空结论，比直接失败更危险。
 */
class ContextBuildNode(
    private val factsPort: EpisodeFactsPort,
    private val budgetPolicy: RcaBudgetPolicy = RcaBudgetPolicy.DEFAULT
) : RcaNodeHandler {

    override val node: RcaNode = RcaNode.CONTEXT_BUILD

    override fun execute(context: RcaNodeContext): RcaNodeOutcome {
        val episodeId = context.state.episodeId
        val facts = factsPort.load(episodeId)
            ?: return RcaNodeOutcome(
                state = context.state,
                next = null,
                enterReason = "任务创建后进入上下文构建",
                leaveReason = "问题对象 $episodeId 的事实不可读，无可锚定内容，直接转人工而不是产出空结论",
                terminalStatus = com.feedback.agent.rca.domain.RcaTaskStatus.NEEDS_HUMAN,
                stopReason = com.feedback.agent.rca.domain.RcaStopReason.HUMAN_REQUIRED
            )

        val profile = ProblemProfile(
            tenantKey = facts.tenantKey,
            appId = facts.appId,
            problemKey = facts.problemKey,
            episodeGeneration = facts.generation,
            errorSignature = facts.errorSignature,
            occurredFrom = facts.occurredFrom,
            occurredTo = facts.occurredTo,
            distinctDeviceCount = facts.impact.distinctDeviceCount,
            distinctUserCount = facts.impact.distinctUserCount,
            repeatCount = facts.impact.repeatCount,
            crashOrAnr = facts.crashOrAnr,
            hasValidLogId = facts.hasValidLogId,
            // 日志 ID 与对照组版本必须一起带进画像：门禁判据（hasValidLogId、跨版本）
            // 只说明「该不该查」，真正构造调用入参时用的是这两个值。
            // 漏掉它们会让强制项在基线节点变成「无法构造合法参数」的证据缺口——
            // 一个本该完成的强制项被记成缺口，整条计划就永远无法完成。
            logId = facts.logId,
            clusterMemberCount = facts.clusterMemberCount,
            clusterId = facts.clusterId,
            affectedVersions = facts.affectedVersions,
            controlVersion = facts.controlVersion,
            symptomSummary = facts.symptomSummary
        )

        val plan = MandatoryEvidencePlan.from(profile)
        val budget = budgetPolicy.initialBudget(context.state.gapsAndBudget.now, facts.occurredTo)

        val next = context.state.copy(
            problemProfile = profile,
            mandatoryPlan = plan,
            gapsAndBudget = budget,
            stateVersion = context.state.stateVersion + 1
        )

        return RcaNodeOutcome(
            state = next,
            next = RcaNode.BASELINE_EVIDENCE,
            enterReason = "任务创建后进入上下文构建",
            leaveReason = buildString {
                append("生成问题画像与 ${plan.requirements.size} 项强制证据：")
                append(
                    plan.requirements.joinToString { it.code }
                        .ifBlank { "无（无合法日志 ID、无 Crash/ANR、单成员、单版本）" }
                )
            }
        )
    }
}

/**
 * 预算策略。
 *
 * 与文档口径一致：受步数、超时和费用三重限制。单独成类是因为这些数值会随试点数据调整，
 * 调整时必须可追溯——所以策略对象带版本号。
 *
 * **`policyVersion` 目前没有任何消费方**（`gapsAndBudget` 里只落 `deadlineAt` 等数值，
 * 不落这个版本号），所以「落库到任务快照」这句话今天并不成立，如实记在交付文档的未决项里。
 * 真正可追溯的是生效后的截止时间本身：它作为 `GapsAndBudget.deadlineAt` 随状态快照落库。
 */
data class RcaBudgetPolicy(
    val policyVersion: String,
    val maxSteps: Int,
    val maxTokens: Int,
    val maxCostUsd: BigDecimal,
    /**
     * 任务整体的墙钟上限；超过即按 DEADLINE_EXCEEDED 降级。
     *
     * 生产侧由 [production] 取 `app.rca.task-timeout-minutes`，与 `claim` 写进
     * `t_rca_agent_task.deadline_at` 的那个时长**同源**。两处给出不同的时长不会报任何错，
     * 只会表现为「库上还没超期、图里已经按超期收口」或者反过来，
     * 而那两种现象看起来都像是节点自己的问题。
     */
    val deadline: java.time.Duration
) {
    /**
     * 构造初始预算。
     *
     * 截止时间取「进入上下文构建的时刻 + 策略上限」，**不用故障发生时间**：
     * 用故障时间会让历史问题上来的任务一创建就已超期，从而永远走不到取证。
     *
     * ## 这个时刻与库上的 `deadline_at` 差在哪
     *
     * 库上的 `deadline_at` 是 `claim` 用数据库的 `NOW(3)` 在**领取时刻**写下的；
     * 这里是「进入上下文构建的时刻 + 同一个时长」。两个时刻之间有几步（领取、恢复判定、
     * 快照解密），正常情况是毫秒到秒级，而两个时钟（数据库、Worker 所在机器）之间还有漂移。
     * 因此**两者不保证逐毫秒相等**，这是刻意的取舍：
     *
     * - 「能不能被领取/该不该开始」由库上那一列裁定，用的是数据库时钟；
     * - 「图跑到一半该不该降级」由这里裁定，用的是 Worker 时钟（`GapsAndBudget.now` 会被
     *   各节点刷新，见 [com.feedback.agent.rca.graph.AgentInvestigateNode]）。
     *
     * 两者共同时长、且都以「领取」为起点，所以判定方向一致；若哪天真需要逐毫秒一致，
     * 做法是把库上算出的剩余毫秒带进状态（像租约那样传差值），而不是让 Worker
     * 去读一个绝对时间点再来比——那会把时钟漂移重新引回判断链路。
     */
    fun initialBudget(now: LocalDateTime, episodeLastEventAt: LocalDateTime): GapsAndBudget = GapsAndBudget(
        stepBudget = maxSteps,
        stepsUsed = 0,
        // 动态调查节点只占一部分预算，工具调用与模型推理共享同一个池子，避免「步数没用完但费用已超」。
        tokenBudget = maxTokens,
        tokensUsed = 0,
        costBudgetUsd = maxCostUsd,
        costUsedUsd = BigDecimal.ZERO,
        deadlineAt = now.plus(deadline),
        now = now,
        knownGaps = buildList {
            val age = java.time.Duration.between(episodeLastEventAt, now)
            if (age > STALE_EPISODE_THRESHOLD) {
                add("问题对象最后一次事件距今 ${age.toHours()} 小时，部分日志与变更记录可能已过保留期")
            }
        }
    )

    companion object {
        /** 超过这个时长的问题对象，其日志保留期可能已过，必须提前提示缺口。 */
        val STALE_EPISODE_THRESHOLD: java.time.Duration = java.time.Duration.ofDays(7)

        /**
         * 离线套件（回放与故障注入）用的策略。
         *
         * 它的 15 分钟与生产默认的 30 分钟**不是同一个口径**，也不该被改成同一个：
         * 离线套件用固定时钟、按脚本走完 12 步以内就结束，截止时间在那里只是一个
         * 不会触发的上限；而生产的上限要覆盖真实的上游延迟与模型延迟。
         * 把两者统一成同一个数，只会让离线套件里那个「永远不会触发」的常量变得难解释。
         */
        val DEFAULT: RcaBudgetPolicy = RcaBudgetPolicy(
            policyVersion = "rca-budget/1.0.0",
            maxSteps = 12,
            maxTokens = 60_000,
            maxCostUsd = BigDecimal("0.500000"),
            deadline = java.time.Duration.ofMinutes(15)
        )

        /**
         * 生产策略：与 [DEFAULT] 同源，只有截止时间改由部署配置给出。
         *
         * 之所以做成工厂而不是让调用方自己 `copy`：调用方要拿到「步数/费用也要跟默认一致」
         * 这个事实，而 `copy` 只表达「改一个字段」，两者在改坏时会走得很远——
         * 例如默认值调整后，`copy` 出来的策略会分叉。
         *
         * 步数与费用**没有**跟着配置化（`app.rca` 下也没有对应的键），
         * 因为 `t_rca_agent_task.budget_max_*` 三列是库上的硬约束，
         * 配置给得比它们大只会在 `writeBack` 时以 CHECK 冲突的形式失败。
         */
        fun production(deadlineMinutes: Long): RcaBudgetPolicy =
            DEFAULT.copy(deadline = java.time.Duration.ofMinutes(deadlineMinutes))
    }
}
