package com.feedback.agent.rca.replay

import com.feedback.agent.rca.domain.EpisodeAttributionLevel
import com.feedback.agent.rca.domain.Hypothesis
import com.feedback.agent.rca.domain.HypothesisConfidence
import com.feedback.agent.rca.domain.HypothesisStatus
import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.RcaFallbackEdge
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus
import com.feedback.agent.rca.graph.AgentInvestigateNode
import com.feedback.agent.rca.graph.BaselineEvidenceNode
import com.feedback.agent.rca.graph.ContextBuildNode
import com.feedback.agent.rca.graph.RcaNodeContext
import com.feedback.agent.rca.graph.RcaPersistNode
import com.feedback.agent.rca.graph.RcaRunResult
import com.feedback.agent.rca.graph.RcaStateGraph
import com.feedback.agent.rca.graph.RootCauseArbitrateNode
import com.feedback.agent.rca.tools.ContentStatusTool
import com.feedback.agent.rca.tools.CrashAnrTool
import com.feedback.agent.rca.tools.HistoricalCaseTool
import com.feedback.agent.rca.tools.LogTraceTool
import com.feedback.agent.rca.tools.RcaToolRegistry
import com.feedback.agent.rca.tools.ReleaseChangeTool
import com.feedback.agent.rca.tools.ServiceHealthTool
import com.feedback.agent.rca.tools.ToolInvocationGuard
import com.feedback.agent.rca.tools.VersionCohortTool
import kotlin.system.exitProcess

/**
 * 414 固定回放的入口。
 *
 * 它把夹具、七类工具与五节点状态图按**生产的装配方式**接起来跑一遍，然后断言结论与取证路径。
 * 断言而不是只打印输出，是因为回放的价值在于当回归基线：编排逻辑一改，
 * 取证路径或者结论变了就必须失败，而不是等谁去把输出读一遍。
 *
 * 被替换掉的只有两端——模型（[ScriptedInvestigationModel]）与上游（[ReplayUpstreamPorts]）。
 * 中间的工具层、调用守卫、假设更新、停止判定、仲裁、落库顺序全部是真实代码。
 * 这正是回放能当回归测试用的原因：被替换的部分恰好是唯一不确定的部分。
 * 时间同样被固定（[RcaClock.fixed]），所以同一份夹具在任何时刻执行都得到同一结论。
 *
 * 回放分两个阶段，对应两条独立的正确性主张：
 * 1. **取证路径**：五节点各进入一次、工具按预期顺序被调用、终态与归因等级符合预期。
 * 2. **持久化重放**：把持久化节点在同一个状态上再执行一次，验证幂等——
 *    这是「仲裁完成但尚未落库」这个窗口崩溃后能安全恢复的前提。
 *
 * 运行：`./gradlew :backend:rcaReplay414`（见 build.gradle.kts 的 `rcaReplay414` 任务）。
 */
object RcaReplay {

    /** 回放时间锚点之后 3 小时作为「现在」：晚于所有数据水位，也不至于让问题对象显得过旧。 */
    private val NOW_OFFSET_HOURS: Long = 3

    fun run(): RcaReplayOutcome {
        val facts = ReplayScenario.episodeFacts()
        val scenario = ReplayScenario.of()
        // 上游端口与签名分布投影共用一个留痕器：它们记的是同一条取证路径。
        val recorder = ReplayCallRecorder()
        val ports = ReplayUpstreamPorts(facts, scenario, recorder)
        val signaturePort = ReplayClusteredSignaturePort(facts, scenario, recorder)
        val clock = RcaClock.fixed(ReplayScenario.FAULT_AT.plusHours(NOW_OFFSET_HOURS))

        val guard = ToolInvocationGuard(
            RcaToolRegistry(
                listOf(
                    LogTraceTool(ports, clock),
                    ReleaseChangeTool(ports, clock),
                    VersionCohortTool(ports, clock),
                    ServiceHealthTool(ports, clock),
                    CrashAnrTool(ports, clock),
                    ContentStatusTool(ports, clock),
                    HistoricalCaseTool(ports, clock)
                )
            )
        )
        val persistence = InMemoryRcaPersistencePort()
        val graph = RcaStateGraph(
            listOf(
                ContextBuildNode(ports),
                BaselineEvidenceNode(guard, signaturePort),
                AgentInvestigateNode(ScriptedInvestigationModel(), guard, clock = clock),
                RootCauseArbitrateNode(clock),
                RcaPersistNode(persistence)
            )
        )

        val result = graph.run(
            RcaSharedState.seeded(
                taskId = ReplayScenario.TASK_ID,
                episodeId = ReplayScenario.EPISODE_ID,
                now = clock.now()
            )
        )
        val checks = ReplayChecks()
        verifyInvestigation(result, ports, checks)
        verifyPersistReplay(result, persistence, checks)

        return RcaReplayOutcome(result, ports, persistence, checks)
    }

    // -----------------------------------------------------------------------
    // 断言一：取证路径与结论
    // -----------------------------------------------------------------------

    /**
     * 断言的是**路径与结论**，不只是结论。
     *
     * 只看结论会漏掉「用错工具但碰巧结论一样」这一类退化：例如模型没查发布变更，
     * 却因为别处的证据凑巧推出了同一个根因。所以工具调用序列、证据集合、
     * 归因等级都要逐项对上。
     */
    private fun verifyInvestigation(result: RcaRunResult, ports: ReplayUpstreamPorts, checks: ReplayChecks) {
        val state = result.finalState

        checks.equal("终态", RcaTaskStatus.COMPLETED, result.status)
        checks.equal("停止原因", RcaStopReason.EVIDENCE_SUFFICIENT, result.stopReason)
        checks.equal("回退次数", 0, result.arbitrateRevisions)
        checks.equal(
            "节点进入次数",
            RcaNode.FORWARD_ORDER.joinToString("、") { "${it.storageCode}×1" },
            RcaNode.FORWARD_ORDER.joinToString("、") { "${it.storageCode}×${result.nodeVisits[it] ?: 0}" }
        )

        // 工具调用序列：前三项由强制证据计划产生（含签名分布，它不走工具），
        // 后五项由调查循环按剧本产生。序列本身就是「取证路径」的可读形式。
        checks.equal(
            "工具调用序列",
            "LOG_TRACE,VERSION_COHORT,LOG_TRACE,CRASH_ANR,RELEASE_CHANGE,SERVICE_HEALTH,VERSION_COHORT",
            state.toolHistory.joinToString(",") { it.toolName }
        )
        checks.equal(
            "各调用的决策轮次",
            "0,0,1,2,3,4,5",
            state.toolHistory.joinToString(",") { it.decisionRound.toString() }
        )
        checks.equal("证据条数", EXPECTED_EVIDENCE_COUNT, state.evidenceLedger.size)
        checks.equal("未排除的强反证", 0, state.unresolvedRefutationCount)
        checks.equal("强制证据计划完成", true, state.mandatoryPlan.allSatisfiedOrWaived)

        // 上游调用分布：按类别计数，键排序后拼成可读的字符串。
        // 计数里只会出现真的被问过的类别，所以「一次都没问」的类别由下一条断言单独覆盖。
        checks.equal(
            "上游调用分布",
            "CLUSTER_SIGNATURE=1, CRASH_ANR=1, LOG_TRACE=2, RELEASE_CHANGE=1, SERVICE_HEALTH=1, VERSION_COHORT=2",
            ports.calls.groupingBy { it.substringBefore('(') }.eachCount()
                .entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }
        )
        // 断言「没被调用」和断言「被调用过」一样重要：两者都是取证路径的一部分。
        // 内容状态与历史案例在本次调查里确实不需要取证——「需要」是被论证出来的，
        // 一旦编排开始随手拉这两类上游，这里会立刻失败。
        checks.equal(
            "未取证的上游类别",
            "CONTENT_STATUS,HISTORICAL_CASE",
            UPSTREAM_CATEGORIES.filterNot { category -> ports.calls.any { it.startsWith("$category(") } }
                .joinToString(",")
        )

        val refuted = hypothesis(state, ScriptedInvestigationModel.CLIENT_DECODE_CODE)
        checks.equal("H1 状态", HypothesisStatus.REFUTED, refuted?.status)
        checks.equal("H1 置信等级", HypothesisConfidence.REFUTED, refuted?.confidence)
        checks.equal("H1 被反证引用数", 1, refuted?.refutingEvidenceIds?.size)

        val leading = hypothesis(state, ScriptedInvestigationModel.AUTH_CONTRACT_CODE)
        checks.equal("H2 状态", HypothesisStatus.SUPPORTED, leading?.status)
        checks.equal("H2 置信等级", HypothesisConfidence.STRONG, leading?.confidence)
        checks.equal("H2 支撑证据数", 3, leading?.supportingEvidenceIds?.size)

        val arbitration = state.arbitration
        checks.equal("归因等级", EpisodeAttributionLevel.DIRECT_CONFIRMED, arbitration?.attribution?.level)
        checks.equal("自动关联 Incident", true, arbitration?.autoLinkPermitted)
        checks.equal("稳定根因签名", EXPECTED_SIGNATURE, arbitration?.attribution?.signature?.canonical)
        checks.equal("归因依据证据数", 3, arbitration?.attribution?.basisEvidenceIds?.size)
        checks.equal("逐对引用校验配对数", 3, arbitration?.claimPairs?.size)
        checks.equal(
            "粒度不足的配对（范围性结论引用了单点证据，属诊断项而非违规）",
            2,
            arbitration?.claimPairs?.count { it.verdict.storageCode == "PARTIAL_OVERSCOPED" }
        )
    }

    // -----------------------------------------------------------------------
    // 断言二：持久化节点可重放
    // -----------------------------------------------------------------------

    /**
     * 在同一个状态上再执行一次持久化节点，断言没有多出行。
     *
     * 这一步模拟的是崩溃恢复：任务在「归因已写库、任务行尚未置终态」处挂掉，
     * 恢复后从头再跑一遍持久化节点。如果写入不是幂等的，这里会多出一条归因，
     * 而追加式表设计让这种重复极难在事后被发现。
     */
    private fun verifyPersistReplay(
        result: RcaRunResult,
        persistence: InMemoryRcaPersistencePort,
        checks: ReplayChecks
    ) {
        val evidenceBefore = persistence.evidence.size
        val attributionsBefore = persistence.attributions.size
        val incidentsBefore = persistence.incidentCount
        val knowledgeBefore = persistence.knowledgeCandidateCount

        RcaPersistNode(persistence).execute(RcaNodeContext(result.finalState, attemptNo = 1))

        checks.equal("重放后证据行数不变", evidenceBefore, persistence.evidence.size)
        checks.equal("重放后归因行数不变", attributionsBefore, persistence.attributions.size)
        checks.equal("重放后 Incident 关联数不变", incidentsBefore, persistence.incidentCount)
        checks.equal("重放后知识候选数不变", knowledgeBefore, persistence.knowledgeCandidateCount)
        checks.equal("归因行数", 1, persistence.attributions.size)
        checks.equal("Incident 关联数", 1, persistence.incidentCount)
        checks.equal("知识候选数", 1, persistence.knowledgeCandidateCount)
    }

    private fun hypothesis(state: RcaSharedState, code: String): Hypothesis? =
        state.hypotheses.firstOrNull { it.code == code }

    /** 预期证据条数：强制证据 3 条（日志、签名分布、版本对照）+ 调查循环 5 条。 */
    private const val EXPECTED_EVIDENCE_COUNT = 8

    /**
     * 会被留痕的上游类别，与 [ReplayCallRecorder] 记录的前缀一致。
     *
     * 含簇内签名分布与七类工具：它在契约上不算上游端口，但在取证路径上与它们同列，
     * 所以一起纳入「哪些类别被问过」的统计口径。
     */
    private val UPSTREAM_CATEGORIES: List<String> = listOf(
        "LOG_TRACE", "RELEASE_CHANGE", "VERSION_COHORT", "SERVICE_HEALTH",
        "CRASH_ANR", "CONTENT_STATUS", "HISTORICAL_CASE", "CLUSTER_SIGNATURE"
    )

    /** 预期稳定签名：租户 | 组件 | 故障类型 | 错误签名 | 接口类型。 */
    private const val EXPECTED_SIGNATURE =
        "DEFAULT|resource-auth-service|PARAMETER_INCOMPATIBILITY|HTTP_400@/api/resource/auth|HTTP_400"
}

/** 一次回放的产物：运行结果、上游留痕、落库留痕与断言结论。 */
data class RcaReplayOutcome(
    val result: RcaRunResult,
    val ports: ReplayUpstreamPorts,
    val persistence: InMemoryRcaPersistencePort,
    val checks: ReplayChecks
) {
    val passed: Boolean get() = checks.passed

    /** 人读报告。结论在前、明细在后：出问题时先看结论，需要追因时再往下读。 */
    fun report(): String = buildString {
        val state = result.finalState
        val arbitration = state.arbitration

        appendLine("=== 414 视频鉴权失败：固定回放 ===")
        appendLine()
        appendLine("[结论]")
        appendLine("终态            ${result.status.storageCode}（停止原因 ${result.stopReason.storageCode}）")
        appendLine("归因等级        ${arbitration?.attribution?.level?.storageCode}")
        appendLine("稳定根因签名    ${arbitration?.attribution?.signature?.canonical}")
        appendLine(
            "自动关联        " +
                (if (arbitration?.autoLinkPermitted == true) "允许（前两档才允许）" else "不允许")
        )
        appendLine("回退次数        ${result.arbitrateRevisions}（上限 ${RcaFallbackEdge.MAX_ARBITRATE_REVISIONS}）")
        arbitration?.supportRatio?.let {
            appendLine("引用支持正确率  ${"%.2f".format(it)}（范围性结论只认 cohort 级引用，单点引用计为粒度不足）")
        }

        appendLine()
        appendLine("[状态图留痕]")
        result.steps.forEach { step ->
            appendLine(
                "  ${step.node.storageCode}#${step.attemptNo} " +
                    "v${step.stateVersionBefore}→${step.stateVersionAfter} ${step.elapsedMs}ms"
            )
            appendLine("    进入：${step.enterReason}")
            appendLine("    离开：${step.leaveReason}")
            step.fallbackTrigger?.let { appendLine("    回退触发：$it") }
        }

        appendLine()
        appendLine("[假设]")
        state.hypotheses.forEach { hypothesis ->
            appendLine(
                "  ${hypothesis.code} ${hypothesis.status.storageCode}/${hypothesis.confidence.storageCode} " +
                    "rev${hypothesis.revision} 支撑${hypothesis.supportingEvidenceIds.size} " +
                    "反证${hypothesis.refutingEvidenceIds.size}"
            )
            appendLine("    ${hypothesis.statement}")
            hypothesis.mechanismNote?.let { appendLine("    机制：$it") }
        }

        appendLine()
        appendLine("[证据账本]")
        state.evidenceLedger.forEach { evidence ->
            appendLine(
                "  ${evidence.evidenceId} ${evidence.type.storageCode} " +
                    "覆盖=${evidence.coverageType.storageCode}"
            )
            appendLine("    ${evidence.summary}")
            appendLine("    回源：${evidence.sourceRef}")
        }

        appendLine()
        appendLine("[强制证据计划]")
        state.mandatoryPlan.requirements.forEach { requirement ->
            appendLine(
                "  ${requirement.code} ${requirement.completion.storageCode}" +
                    (requirement.failureSemantic?.let { " 失败语义=${it.storageCode}" } ?: "") +
                    (requirement.satisfiedByEvidenceId?.let { " 满足者=$it" } ?: "")
            )
        }

        appendLine()
        appendLine("[缺口与预算]")
        appendLine(
            "  步数 ${state.gapsAndBudget.stepsUsed}/${state.gapsAndBudget.stepBudget}，" +
                "token ${state.gapsAndBudget.tokensUsed}/${state.gapsAndBudget.tokenBudget}，" +
                "费用 ${state.gapsAndBudget.costUsedUsd}/${state.gapsAndBudget.costBudgetUsd}，" +
                "截止 ${state.gapsAndBudget.deadlineAt}（当前时刻 ${state.gapsAndBudget.now}，固定时钟）"
        )
        state.gapsAndBudget.knownGaps.forEach { appendLine("  缺口：$it") }
        state.stopProposal?.knownGaps?.forEach { appendLine("  提案记录的缺口：$it") }

        appendLine()
        appendLine("[上游调用]")
        ports.calls.forEach { appendLine("  $it") }

        appendLine()
        appendLine("[落库]")
        appendLine("  证据 ${persistence.evidence.size} 行、假设 ${persistence.hypotheses.size} 行、" +
            "强制证据 ${persistence.mandatoryRequirements.size} 行、归因 ${persistence.attributions.size} 行、" +
            "Incident ${persistence.incidentCount} 个、知识候选 ${persistence.knowledgeCandidateCount} 个")

        appendLine()
        appendLine("[断言]")
        checks.failureLines().forEach { appendLine("  失败：$it") }
        appendLine("  ${if (passed) "全部通过（${checks.total} 项）" else "未通过"}")
    }
}

/**
 * 断言收集器：跑完全部断言再汇总，而不是遇到第一个失败就中断。
 *
 * 一次性看到所有不符，才能判断是「一处改动」还是「整条路径都变了」——
 * 后者往往意味着编排装配出了问题，而前者只是一个预期值需要更新。
 */
class ReplayChecks {

    private val failures: MutableList<String> = mutableListOf()

    /** 已执行的断言数；报告里要给出「全部通过（N 项）」，否则很容易把空断言当成通过。 */
    var total: Int = 0
        private set

    val passed: Boolean get() = failures.isEmpty()

    fun <T> equal(label: String, expected: T, actual: T) {
        total += 1
        if (expected != actual) {
            failures += "$label：预期 $expected，实际 $actual"
        }
    }

    fun failureLines(): List<String> = failures.toList()
}

fun main() {
    val outcome = RcaReplay.run()
    println(outcome.report())
    if (!outcome.passed) {
        // 非零退出码让它在 CI 或脚本里能当回归基线用，而不只是给人读的一段输出。
        exitProcess(1)
    }
}
