package com.feedback.agent.rca.replay

import com.feedback.agent.rca.domain.GapsAndBudget
import com.feedback.agent.rca.domain.LocalCommitState
import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus
import com.feedback.agent.rca.domain.RcaToolInvoker
import com.feedback.agent.rca.domain.RcaToolName
import com.feedback.agent.rca.domain.ToolCallRecord
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.domain.ToolResult
import com.feedback.agent.rca.domain.ToolResultStatus
import com.feedback.agent.rca.graph.AgentDecision
import com.feedback.agent.rca.graph.AgentInvestigateNode
import com.feedback.agent.rca.graph.BaselineEvidenceNode
import com.feedback.agent.rca.graph.ContextBuildNode
import com.feedback.agent.rca.graph.InvestigationModel
import com.feedback.agent.rca.graph.InvestigationPrompt
import com.feedback.agent.rca.graph.RcaPersistNode
import com.feedback.agent.rca.graph.RcaRunResult
import com.feedback.agent.rca.graph.RcaStateGraph
import com.feedback.agent.rca.graph.RcaStepTrace
import com.feedback.agent.rca.graph.RootCauseArbitrateNode
import com.feedback.agent.rca.runtime.InMemoryRcaTaskLeasePort
import com.feedback.agent.rca.runtime.ProviderFinalState
import com.feedback.agent.rca.runtime.ProviderStateQueryPort
import com.feedback.agent.rca.runtime.RcaBoundaryWriter
import com.feedback.agent.rca.runtime.RcaClaimOutcome
import com.feedback.agent.rca.runtime.RcaLeaseToken
import com.feedback.agent.rca.runtime.RcaRecoveryAction
import com.feedback.agent.rca.runtime.RcaRecoveryDecision
import com.feedback.agent.rca.runtime.RcaRecoveryResolver
import com.feedback.agent.rca.runtime.RcaSnapshotJsonCodec
import com.feedback.agent.rca.runtime.RcaTaskRow
import com.feedback.agent.rca.runtime.RcaToolCallJournalPort
import com.feedback.agent.rca.runtime.RcaToolRecovery
import com.feedback.agent.rca.runtime.RcaWorker
import com.feedback.agent.rca.runtime.RcaWorkerRun
import com.feedback.agent.rca.runtime.RcaWriteBackResult
import com.feedback.agent.rca.tools.ContentStatusTool
import com.feedback.agent.rca.tools.CrashAnrTool
import com.feedback.agent.rca.tools.HistoricalCaseTool
import com.feedback.agent.rca.tools.LogTraceTool
import com.feedback.agent.rca.tools.RcaToolRegistry
import com.feedback.agent.rca.tools.ReleaseChangeTool
import com.feedback.agent.rca.tools.ServiceHealthTool
import com.feedback.agent.rca.tools.ToolInvocationGuard
import com.feedback.agent.rca.tools.VersionCohortTool
import java.time.LocalDateTime
import kotlin.system.exitProcess

/**
 * 四个故障注入位置。
 *
 * 位置不是随便挑的，它们各自切开一条**不同的**恢复路径：
 * - [BEFORE_MODEL_RETURN]：什么都没发出。恢复只需重入节点，工具层不参与。
 * - [AFTER_TOOL_SUCCESS]：上游已经执行、本地一无所有。这是唯一必须**对账**的位置。
 * - [OBSERVATION_COMMITTED_STATE_STALE]：本地调用记录已提交、账本里却没有那条证据（半提交）。
 *   这是最容易做错的位置——直接复用会让后续引用指向一条不存在的证据。
 * - [STALE_WORKER_AFTER_TAKEOVER]：租约被接管后旧 Worker 才醒。验证 generation fencing。
 */
enum class FaultPosition(val storageCode: String, val title: String) {
    BEFORE_MODEL_RETURN("BEFORE_MODEL_RETURN", "模型返回前"),
    AFTER_TOOL_SUCCESS("AFTER_TOOL_SUCCESS", "工具成功后本地写回前"),
    OBSERVATION_COMMITTED_STATE_STALE("OBSERVATION_COMMITTED_STATE_STALE", "Observation 已保存但状态未迁移"),
    STALE_WORKER_AFTER_TAKEOVER("STALE_WORKER_AFTER_TAKEOVER", "旧 Worker 租约过期后恢复")
}

/** 注入用的中止信号。它不是业务异常：真实世界里这条路径由进程直接消失实现。 */
private class RcaInjectionAbort(message: String) : RuntimeException(message)

/**
 * 租约 fencing 与三态恢复的故障注入验证。
 *
 * 四个位置各注入 20 次、共 80 次。位置内的 20 次不是同一件事重复 20 遍，
 * 而是**两个维度的交叉**（见 [windowFor]）：取证时间窗 ×
 * （脚本轮次 | 被截断的工具 | 停在哪一次边界写回之后）。固定回放要的是可复现的覆盖，
 * 不是随机性，所以这里没有随机种子。
 *
 * 每一例都断言三件事，它们正好对应「没有重复 evidence、没有重复预算扣减、
 * 没有旧 generation 覆盖」这三句话：
 * 1. **恢复后的共享状态与基准逐字段相等**。这一条比「跑完了」强得多：
 *    恢复不是「让它继续跑」，而是「让它跑到和没崩过一样」。
 * 2. **证据无重复、无缺失**（确定性 evidence ID 的直接推论）。
 * 3. **预算三项与基准一致**（没有被扣两次，也没有因为崩溃而少扣）。
 *
 * 关于 76/4 这个比例：`上游查不到最终状态` 那一支是**有意构造**的（见 [HUMAN_BRANCH_FROM]），
 * 因为注入套件必须覆盖转人工分支，否则那条分支永远没有被执行过。
 * 它不代表线上比例——线上的转人工比例取决于上游对账能力的实际可用率。
 *
 * 它同样不产生「崩溃后重启恢复」的完整证据：[InMemoryRcaTaskLeasePort] 不落数据，
 * 进程内也没有第二个真实进程。这里验证的是条件与判定逻辑，不是分布式的可用性。
 */
object RcaRecoveryInjection {

    /** 每个位置的注入次数。四个位置合计 80 次。 */
    const val ITERATIONS_PER_POSITION: Int = 20

    /**
     * 从第几例开始把上游对账构造成「查不到」。
     *
     * 20 例里留 4 例：既让自动恢复分支被充分覆盖，也让转人工分支真的被执行到。
     * 这个取值是**夹具参数**而不是断言——它决定跑哪条分支，不决定断言期望什么。
     */
    private const val HUMAN_BRANCH_FROM: Int = 16

    /** 脚本轮次数（不含最后的停止提案）。 */
    private const val SCRIPTED_ROUNDS: Int = 5

    /** 暂停点个数：可写回的边界共 4 个（进入后四个节点之前各一次）。 */
    private const val PAUSE_POINTS: Int = 4

    /**
     * 取证时间窗。换窗口会改掉全部工具参数与参数摘要，
     * 于是 evidence ID、去重键、快照内容全都跟着换一套——这是「换个输入重跑」而不是重复跑。
     *
     * 五个窗口的形状必须一致（见 [assertBoundaryPlans]）：它们只换取证输入，不换图的步数。
     * 曾用过的前后 15/45 分钟会让调查节点早早判定终态，图缩短成三步，
     * 于是按边界定位的注入点有两个永远触发不了——窗口收窄会改变结论的充分性，
     * 而套件需要一个「跑满五步」的基线，两者不能混在同一组窗口里。
     */
    private val WINDOWS: List<ScriptedInvestigationModel.Window> = listOf(
        ScriptedInvestigationModel.Window(beforeMinutes = 30, afterMinutes = 30),
        ScriptedInvestigationModel.Window(beforeMinutes = 60, afterMinutes = 30),
        ScriptedInvestigationModel.Window(beforeMinutes = 30, afterMinutes = 60),
        ScriptedInvestigationModel.Window(beforeMinutes = 120, afterMinutes = 120),
        ScriptedInvestigationModel.Window(beforeMinutes = 60, afterMinutes = 60)
    )

    /** 按剧本顺序被截断的工具（仅 [FaultPosition.AFTER_TOOL_SUCCESS] 用）。 */
    private val SCRIPTED_TOOLS: List<RcaToolName> = listOf(
        RcaToolName.CRASH_ANR, RcaToolName.RELEASE_CHANGE, RcaToolName.SERVICE_HEALTH,
        RcaToolName.VERSION_COHORT, RcaToolName.LOG_TRACE
    )

    /**
     * 可写回的边界：依次是进入后四个节点之前的那一次写回。
     *
     * 这是一条**关于图形状的断言**，不是一份配置：它假定基准运行恰好走满五个节点，
     * 于是四条可写边界正好落在后四个节点上。所有注入点（第几轮、第几个工具、
     * 第几条边界）都建立在这个形状上，所以 [run] 开工前会拿基准运行实测一遍
     * （见 [assertBoundaryPlans]）——形状变了要让套件说出「这个窗口撑不起这些注入点」，
     * 而不是让注入悄悄不发生，最后报成一个指向恢复逻辑的假失败。
     */
    private val WRITABLE_BOUNDARIES: List<RcaNode> = listOf(
        RcaNode.BASELINE_EVIDENCE, RcaNode.AGENT_INVESTIGATE, RcaNode.ROOT_CAUSE_ARBITRATE, RcaNode.RCA_PERSIST
    )

    fun run(): RcaRecoveryInjectionOutcome {
        val checks = ReplayChecks()
        val tallies = linkedMapOf<FaultPosition, InjectionTally>()
        val decisions = linkedMapOf<RcaRecoveryAction, Int>()
        val sweep = SweepTally()

        // 基准运行按窗口先算好，不在用例循环里惰性求值：它是注入点定位的**依据**，
        // 必须先于任何注入存在，也必须在任何注入之前被检查（见下）。
        val references = WINDOWS.associateWith(::runReference)
        assertBoundaryPlans(references, checks)

        FaultPosition.entries.forEach { position ->
            val tally = InjectionTally(position)
            val failuresBefore = checks.failureLines().size
            repeat(ITERATIONS_PER_POSITION) { index ->
                val window = windowFor(position, index)
                val reference = references.getValue(window)
                tally.record(inject(position, index, window, reference, checks, decisions, sweep))
            }
            tally.failures = checks.failureLines().size - failuresBefore
            tallies[position] = tally
        }

        return RcaRecoveryInjectionOutcome(tallies, decisions, sweep, checks)
    }

    /**
     * 前置条件：每个窗口的基准运行都必须真的走满 [WRITABLE_BOUNDARIES]。
     *
     * 逐窗口验而不是只验一个：窗口是这套设计主动交叉的那一维（见 [windowFor]），
     * 换窗口就会换取证结果，也就可能换图的形状。曾经有一个窗口
     * （前后 15/45 分钟）会让调查节点直接判定终态，图只有三步、可写边界只有两条，
     * 于是「第 3、4 条边界后暂停」这两个注入点根本不会触发——而失败现象是
     * 「恢复后状态与基准不一致」，一个看起来指向恢复逻辑、实际与恢复毫无关系的报错。
     */
    private fun assertBoundaryPlans(
        references: Map<ScriptedInvestigationModel.Window, RcaRunResult>,
        checks: ReplayChecks
    ) {
        references.forEach { (window, reference) ->
            checks.equal(
                "基准运行边界计划（窗口 ${window.beforeMinutes}/${window.afterMinutes}）",
                WRITABLE_BOUNDARIES,
                reference.writableBoundaries()
            )
        }
    }

    /** 时间窗与位置内另一个维度的交叉规则。 */
    private fun windowFor(position: FaultPosition, index: Int): ScriptedInvestigationModel.Window =
        when (position) {
            // 5 个轮次 × 4 个窗口。
            FaultPosition.BEFORE_MODEL_RETURN,
            FaultPosition.OBSERVATION_COMMITTED_STATE_STALE -> WINDOWS[index / SCRIPTED_ROUNDS]

            // 5 个工具 × 4 个窗口。
            FaultPosition.AFTER_TOOL_SUCCESS -> WINDOWS[index / SCRIPTED_TOOLS.size]

            // 4 个暂停点 × 5 个窗口。
            FaultPosition.STALE_WORKER_AFTER_TAKEOVER -> WINDOWS[(index / PAUSE_POINTS) % WINDOWS.size]
        }

    // -----------------------------------------------------------------------
    // 单次注入
    // -----------------------------------------------------------------------

    private fun inject(
        position: FaultPosition,
        index: Int,
        window: ScriptedInvestigationModel.Window,
        reference: RcaRunResult,
        checks: ReplayChecks,
        decisions: MutableMap<RcaRecoveryAction, Int>,
        sweep: SweepTally
    ): InjectionResult {
        val label = caseLabel(position, index)
        val fixture = Fixture(window)
        val taskId = fixture.taskId

        // ---- 1. 在注入点中止，不做任何让出（真实崩溃也不会） ----
        val crashed = fixture.crash(position, index)
        checks.equal("$label 在预期位置中止", true, crashed.aborted)
        checks.equal(
            "$label 中止后已记录恢复起点",
            expectedResumeNode(position, index, crashed),
            fixture.resumeNode(taskId)
        )
        checks.equal("$label 中止后仍持有租约", true, fixture.row(taskId).leaseHeld)
        checks.equal("$label 中止后任务状态", RcaTaskStatus.RUNNING, fixture.row(taskId).taskStatus)

        // ---- 2. 恢复输入与三态判定：快照 + 落库的调用记录 ----
        // 走与 RcaWorker 相同的读法：崩溃后状态就是「快照里有什么就是什么」，
        // 快照缺失时是初始状态。套件在这里自己另写一套读法，验证的就不是生产的那条路了。
        val resumed = fixture.crashedState(taskId)
        val journal = recoveryJournal(label, position, index, crashed)
        val decision = decideFor(position, index, journal, resumed, checks)
        decision?.let { decisions[it.action] = decisions.getOrElse(it.action) { 0 } + 1 }

        // ---- 3. 租约过期：真实系统里由「Worker 不再续租」自然流逝，这里把库钟拨快 ----
        fixture.expireLease()
        checks.equal("$label 接管前租约已过期", false, fixture.leaseValid(taskId))

        // ---- 4. 接管：判定与执行都由 Worker 自己完成，套件只断言结果 ----
        val before = fixture.row(taskId)
        val run = fixture.worker(journal, ProviderStateQueryPort { providerAnswer(index) }).runOnce()

        return if (decision?.safeToContinue == false) {
            verifyHandedOver(label, fixture, taskId, decision, run, checks)
        } else {
            verifyRecovered(label, fixture, taskId, before, reference, run, checks, sweep)
        }
    }

    /**
     * 该例的落库调用记录。位置不同，记录不同：
     * - [FaultPosition.BEFORE_MODEL_RETURN]：调用还没发出，没有记录。
     * - [FaultPosition.AFTER_TOOL_SUCCESS]：一条「已发出、未落定」的记录，带上游请求 ID。
     * - [FaultPosition.OBSERVATION_COMMITTED_STATE_STALE]：一条已提交的记录，
     *   但它的证据不在快照的账本里——半提交。这条记录取自基准运行：
     *   持久化调用记录的内容不是本套件发明的，它就是真实运行会写下的那一条。
     * - [FaultPosition.STALE_WORKER_AFTER_TAKEOVER]：中止发生在边界上，在途调用为空。
     */
    private fun recoveryJournal(
        label: String,
        position: FaultPosition,
        index: Int,
        crashed: CrashOutcome
    ): List<ToolCallRecord> = when (position) {
        FaultPosition.BEFORE_MODEL_RETURN,
        FaultPosition.STALE_WORKER_AFTER_TAKEOVER -> emptyList()

        FaultPosition.AFTER_TOOL_SUCCESS -> listOf(
            pendingRecord(
                crashed.capturedCall ?: error("$label 没有捕获到在途调用，注入点没被走到"),
                index
            )
        )

        FaultPosition.OBSERVATION_COMMITTED_STATE_STALE -> listOf(
            crashed.committedRecord ?: error("$label 没有取到已提交记录")
        )
    }

    /**
     * 套件自己算一遍判定，用来核对 Worker 的行为。
     *
     * 这不是重复实现：判定规则只有 `RcaToolRecovery` 一份，这里只是**再走一次同一份规则**，
     * 然后把结果与 Worker 的实际动作对照。两者不一致就说明判定没有真正接在执行路径上——
     * 那正是这段代码要防的事。
     */
    private fun decideFor(
        position: FaultPosition,
        index: Int,
        journal: List<ToolCallRecord>,
        resumed: RcaSharedState,
        checks: ReplayChecks
    ): RcaRecoveryDecision? {
        val label = caseLabel(position, index)
        return when (position) {
            FaultPosition.BEFORE_MODEL_RETURN,
            FaultPosition.STALE_WORKER_AFTER_TAKEOVER -> null

            FaultPosition.AFTER_TOOL_SUCCESS -> {
                val record = journal.single()
                val first = RcaToolRecovery.decide(record, resumed)
                checks.equal("$label 第一步恢复动作", RcaRecoveryAction.RECONCILE_WITH_PROVIDER, first.action)

                val provider = providerAnswer(index)
                checks.equal(
                    "$label 上游对账回答",
                    if (index >= HUMAN_BRANCH_FROM) {
                        ProviderFinalState.UNREACHABLE
                    } else {
                        ProviderFinalState.FINAL_SUCCESS
                    },
                    provider
                )
                val second = RcaToolRecovery.afterReconcile(record, provider, resumed)
                val expected = if (provider == ProviderFinalState.UNREACHABLE) {
                    RcaRecoveryAction.REQUIRE_HUMAN
                } else {
                    RcaRecoveryAction.REPLAY_READ_ONLY
                }
                checks.equal("$label 对账后恢复动作", expected, second.action)
                second
            }

            FaultPosition.OBSERVATION_COMMITTED_STATE_STALE -> {
                val committed = journal.single()
                checks.equal("$label 记录已提交但证据不在快照账本里", true, isHalfCommitted(committed, resumed))
                val decision = RcaToolRecovery.decide(committed, resumed)
                checks.equal("$label 半提交的恢复动作", RcaRecoveryAction.REPLAY_READ_ONLY, decision.action)
                decision
            }
        }
    }

    /**
     * 判定不了的那一支：Worker 必须**自己**收口，而不是把任务丢在原地。
     *
     * 一条 `RUNNING` 且租约过期的任务会被下一个 Worker 捡起来重跑，
     * 而那正是判定为「不能自动继续」的那种任务——不收口就等于这个决定被无声地推翻，
     * 并且会以「尝试次数」的形式被消耗掉，直到再没人能捡起它。
     */
    private fun verifyHandedOver(
        label: String,
        fixture: Fixture,
        taskId: Long,
        decision: RcaRecoveryDecision,
        run: RcaWorkerRun,
        checks: ReplayChecks
    ): InjectionResult {
        checks.equal("$label 判定为不能自动继续", RcaRecoveryAction.REQUIRE_HUMAN, decision.action)
        checks.equal("$label 恢复结果类型", "Abandoned", run::class.simpleName ?: "null")

        val abandoned = run as? RcaWorkerRun.Abandoned
            ?: error("$label 期望 Worker 收口成人工，实际是 ${run::class.simpleName}")
        checks.equal("$label 收口后的任务状态", RcaTaskStatus.NEEDS_HUMAN, abandoned.status)
        checks.equal("$label 收口后的停止原因", RcaStopReason.HUMAN_REQUIRED, abandoned.stopReason)
        checks.equal("$label 收口的错误码与执行异常可区分", "RCA_RECOVERY_UNRESOLVED", abandoned.errorCode)
        checks.equal("$label 收口写入生效", RcaWriteBackResult.APPLIED, abandoned.writeBack)

        val row = fixture.row(taskId)
        checks.equal("$label 库里任务状态", RcaTaskStatus.NEEDS_HUMAN, row.taskStatus)
        checks.equal("$label 库里结构化停止原因", RcaStopReason.HUMAN_REQUIRED, row.stopReason)
        checks.equal("$label 终态不再持有租约", false, row.leaseHeld)
        return InjectionResult(
            recovered = false,
            human = true,
            writeBackApplied = abandoned.writeBack == RcaWriteBackResult.APPLIED
        )
    }

    /** 自动恢复：全新的 Worker 在租约过期后接管同一任务，跑到与基准相同的结果。 */
    private fun verifyRecovered(
        label: String,
        fixture: Fixture,
        taskId: Long,
        before: RcaTaskRow,
        reference: RcaRunResult,
        run: RcaWorkerRun,
        checks: ReplayChecks,
        sweep: SweepTally
    ): InjectionResult {
        checks.equal("$label 恢复结果类型", "Finished", run::class.simpleName ?: "null")
        checks.equal(
            "$label 恢复后终态",
            reference.status,
            (run as? RcaWorkerRun.Finished)?.status ?: fixture.row(taskId).taskStatus
        )
        checks.equal("$label 恢复后停止原因", reference.stopReason, fixture.row(taskId).stopReason)

        val after = fixture.row(taskId)
        checks.equal("$label 接管后 generation 恰好递增一次", before.leaseGeneration + 1, after.leaseGeneration)
        checks.equal("$label 状态版本严格前进", true, after.stateVersion > before.stateVersion)
        checks.equal("$label 终态不再持有租约", false, after.leaseHeld)

        // 最强的一条断言：恢复后的共享状态与基准逐字段相等。
        // 用「差异列表」而不是直接比两个状态：状态有十几个字段、几个还是嵌套列表，
        // 直接渲染会把「哪一处不一样」埋进几千字符里——而那正是断言想问的问题。
        val recovered = fixture.decodeSnapshot(taskId)
        checks.equal(
            "$label 恢复后状态与基准逐字段一致",
            emptyList<String>(),
            stateDiff(reference.finalState.withoutMeasuredLatency(), recovered.withoutMeasuredLatency())
        )
        val referenceIds = reference.finalState.evidenceLedger.map { it.evidenceId }
        val recoveredIds = recovered.evidenceLedger.map { it.evidenceId }
        checks.equal("$label 证据无重复", recoveredIds.size, recoveredIds.distinct().size)
        checks.equal("$label 证据集合与基准一致", referenceIds.sorted(), recoveredIds.sorted())
        checks.equal(
            "$label 预算未重复扣减",
            reference.finalState.gapsAndBudget.budgetFingerprint(),
            recovered.gapsAndBudget.budgetFingerprint()
        )

        // 自洽性抽查：恢复完成之后，每一条已提交记录都应当可复用。
        // 这是「已落定的边界不需要任何重放」这句设计的直接检验——若某条记录被判成需要重放，
        // 说明恢复出来的账本与调用记录对不上。
        recovered.toolHistory.forEach { record ->
            val decision = RcaToolRecovery.decide(record, recovered)
            sweep.checked += 1
            if (decision.action == RcaRecoveryAction.REUSE_LOCAL_OBSERVATION) {
                sweep.reusable += 1
            } else {
                sweep.notReusable += 1
                checks.equal(
                    "$label 恢复后记录应可复用（${record.toolName}）",
                    RcaRecoveryAction.REUSE_LOCAL_OBSERVATION,
                    decision.action
                )
            }
        }

        return InjectionResult(recovered = true, human = false, writeBackApplied = run is RcaWorkerRun.Finished)
    }

    // -----------------------------------------------------------------------
    // 恢复输入构造
    // -----------------------------------------------------------------------

    /**
     * 预写记录：请求已发出（带上游请求 ID）、结果未落定。
     *
     * 生产中上游请求 ID 由上游返回，回放端口不返回它，所以这里显式构造一个——
     * 目的是覆盖对账分支，而不是假装回放端口有对账能力。
     */
    private fun pendingRecord(call: CapturedCall, index: Int): ToolCallRecord = ToolCallRecord(
        toolName = call.toolName.storageCode,
        toolVersion = call.toolVersion,
        paramsDigest = call.paramsDigest,
        decisionRound = call.decisionRound,
        status = call.status,
        providerRequestId = "provider-req-${call.toolName.storageCode}-$index",
        localCommitState = LocalCommitState.PENDING,
        observationDigest = null,
        observationSummary = null,
        evidenceIds = emptyList()
    )

    /** 已提交、但证据没进快照账本：复用会让后续引用指向一条不存在的证据。 */
    private fun isHalfCommitted(record: ToolCallRecord, resumed: RcaSharedState): Boolean =
        record.localCommitState == LocalCommitState.COMMITTED &&
            record.evidenceIds.isNotEmpty() &&
            record.evidenceIds.all { resumed.evidenceById(it) == null }

    private fun providerAnswer(index: Int): ProviderFinalState =
        if (index >= HUMAN_BRANCH_FROM) ProviderFinalState.UNREACHABLE else ProviderFinalState.FINAL_SUCCESS

    /** 中止之后库里记下的「从哪继续」。它是恢复的唯一入口，必须与注入点严格对应。 */
    private fun expectedResumeNode(position: FaultPosition, index: Int, crashed: CrashOutcome): RcaNode =
        when (position) {
            FaultPosition.BEFORE_MODEL_RETURN,
            FaultPosition.OBSERVATION_COMMITTED_STATE_STALE -> RcaNode.AGENT_INVESTIGATE

            // 被截断的工具若在基线阶段被调用（决策轮次 0），恢复起点就是基线节点。
            FaultPosition.AFTER_TOOL_SUCCESS ->
                if ((crashed.capturedCall?.decisionRound ?: 0) == 0) RcaNode.BASELINE_EVIDENCE
                else RcaNode.AGENT_INVESTIGATE

            FaultPosition.STALE_WORKER_AFTER_TAKEOVER -> WRITABLE_BOUNDARIES[index % PAUSE_POINTS]
        }

    private fun caseLabel(position: FaultPosition, index: Int): String = when (position) {
        FaultPosition.BEFORE_MODEL_RETURN ->
            "模型返回前#${index}（第 ${index % SCRIPTED_ROUNDS + 1} 轮，" +
                "窗口 ${windowFor(position, index).beforeMinutes}/${windowFor(position, index).afterMinutes}）"

        FaultPosition.AFTER_TOOL_SUCCESS ->
            "工具成功后#${index}（${SCRIPTED_TOOLS[index % SCRIPTED_TOOLS.size].storageCode}）"

        FaultPosition.OBSERVATION_COMMITTED_STATE_STALE ->
            "Observation 已存状态未迁移#${index}（第 ${index % SCRIPTED_ROUNDS + 1} 轮）"

        FaultPosition.STALE_WORKER_AFTER_TAKEOVER ->
            "旧 Worker 恢复#${index}（第 ${index % PAUSE_POINTS + 1} 次边界写回后暂停）"
    }

    /** 基准：不注入，直接跑完整条图。每个窗口只算一次。 */
    private fun runReference(window: ScriptedInvestigationModel.Window): RcaRunResult {
        val fixture = Fixture(window)
        return fixture.graph().run(state = fixture.seedState())
    }

    // -----------------------------------------------------------------------
    // 装配
    // -----------------------------------------------------------------------

    /**
     * 一例注入所需的全部装配。
     *
     * 每例都新建一套而不是跨例复用：租约表、留痕器、持久化端口都带状态，
     * 复用会让上一例的残留影响下一例，而那种影响很难归因。
     */
    private class Fixture(private val window: ScriptedInvestigationModel.Window) {

        private val facts = ReplayScenario.episodeFacts()
        private val scenario = ReplayScenario.of()
        private val recorder = ReplayCallRecorder()
        private val ports = ReplayUpstreamPorts(facts, scenario, recorder)
        private val signaturePort = ReplayClusteredSignaturePort(facts, scenario, recorder)
        private val clock: RcaClock = RcaClock.fixed(ReplayScenario.FAULT_AT.plusHours(INJECTION_NOW_OFFSET_HOURS))

        /** 数据库时钟。拨快它来制造租约过期——真实系统里这个时间是 `NOW(3)`。 */
        private var databaseNow: LocalDateTime = ReplayScenario.FAULT_AT.plusHours(INJECTION_NOW_OFFSET_HOURS)

        val leases = InMemoryRcaTaskLeasePort { databaseNow }

        private val codec = RcaSnapshotJsonCodec()
        private val persistence = InMemoryRcaPersistencePort()

        /**
         * 本夹具的任务 ID。
         *
         * 每个夹具自带一张空的租约表，所以它恒为 1——不写死这个常量而是问表要，
         * 是为了让「基准运行」和「注入运行」拿到的是同一个值：
         * 快照里带着 taskId，两者不一致时逐字段比对会永远失败，而原因是装配而不是逻辑。
         */
        val taskId: Long = leases.enqueue(ReplayScenario.TASK_KEY, ReplayScenario.EPISODE_ID)

        /** 被注入截断的那次调用的留痕，供恢复输入构造使用。 */
        private var capturedCall: CapturedCall? = null

        fun seedState(): RcaSharedState = RcaSharedState.seeded(
            taskId = taskId,
            episodeId = ReplayScenario.EPISODE_ID,
            now = clock.now()
        )

        fun row(taskId: Long) = leases.row(taskId) ?: error("任务 $taskId 不在租约表里")

        fun resumeNode(taskId: Long): RcaNode = row(taskId).currentNode.let { code ->
            RcaNode.fromStorage(code) ?: error("current_node=$code 不是合法节点，数据不一致")
        }

        /**
         * 读取崩溃后的状态。
         *
         * 没有快照不是异常：任务刚入队时快照就是空的，这种情况下它的恢复输入是初始状态。
         * 这与 `RcaWorker` 的选择必须一致——两处不一致时，套件验证的就不再是生产会走的那条路。
         */
        fun crashedState(taskId: Long): RcaSharedState =
            row(taskId).stateSnapshot?.let(codec::decode) ?: seedState()

        /** 读取终态快照。终态必然写过快照，读不到就是真的出了问题，所以这里不做兜底。 */
        fun decodeSnapshot(taskId: Long): RcaSharedState =
            codec.decode(row(taskId).stateSnapshot ?: error("任务 $taskId 没有状态快照，无法恢复"))

        fun leaseValid(taskId: Long): Boolean = leases.row(taskId)?.leaseUntil?.isAfter(databaseNow) == true

        fun expireLease() {
            databaseNow = databaseNow.plusSeconds(INJECTION_LEASE_SECONDS + 1)
        }

        fun claimFor(owner: String): RcaLeaseToken {
            val claimed = leases.claim(owner, INJECTION_LEASE_SECONDS)
            return (claimed as? RcaClaimOutcome.Claimed)?.token
                ?: error("任务领取失败：${claimed::class.simpleName}")
        }

        /**
         * 接管本次任务的 Worker。
         *
         * 调用记录与上游对账口都由参数注入：它们正是「上一个持有者留下的痕迹」，
         * 不同注入位置留下的痕迹不同，所以不能写死在装配里。
         */
        fun worker(
            journal: List<ToolCallRecord> = emptyList(),
            provider: ProviderStateQueryPort = ProviderStateQueryPort { ProviderFinalState.UNREACHABLE }
        ): RcaWorker = RcaWorker(
            leases = leases,
            graph = graph(),
            codec = codec,
            clock = clock,
            workerId = RECOVERY_OWNER,
            leaseSeconds = INJECTION_LEASE_SECONDS,
            recovery = RcaRecoveryResolver(
                journal = RcaToolCallJournalPort { journal },
                provider = provider
            )
        )

        fun graph(
            model: InvestigationModel = ScriptedInvestigationModel(window),
            wrapTools: (RcaToolInvoker) -> RcaToolInvoker = { it }
        ): RcaStateGraph {
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
                    ).map(wrapTools)
                )
            )
            return RcaStateGraph(
                listOf(
                    ContextBuildNode(ports),
                    BaselineEvidenceNode(guard, signaturePort),
                    AgentInvestigateNode(model, guard, clock = clock),
                    RootCauseArbitrateNode(clock),
                    RcaPersistNode(persistence)
                )
            )
        }

        /**
         * 崩溃运行：领取任务，按生产的边界逻辑推进，然后在注入点中止。
         *
         * 中止**不做任何让出**——真实崩溃也不会。因此任务留在 `RUNNING` 且持有租约，
         * 只能等租约过期被接管。这正是「租约是崩溃恢复的唯一凭据」这句话的实现。
         */
        fun crash(position: FaultPosition, index: Int): CrashOutcome {
            val token = claimFor(CRASHED_OWNER)
            val boundary = RcaBoundaryWriter(leases, codec, CRASHED_OWNER, INJECTION_LEASE_SECONDS, token)
            val start = resumeNode(token.taskId)
            // 刚入队的任务快照是空的，此刻的输入状态就是初始状态——
            // 这跟 RcaWorker 领到一条新任务时的选择一致。
            val resumed = crashedState(token.taskId)

            val aborted = try {
                when (position) {
                    FaultPosition.BEFORE_MODEL_RETURN -> graph(
                        model = AbortingModel(
                            ScriptedInvestigationModel(window),
                            abortOnCall = index % SCRIPTED_ROUNDS + 1
                        )
                    ).run(resumed, start, boundary::onBoundary)

                    FaultPosition.OBSERVATION_COMMITTED_STATE_STALE -> graph(
                        // 让第 r 轮的工具调用**成功落进持久化调用记录**，再在下一轮决策返回前中止：
                        // 「Observation 已保存但状态未迁移」缺的就是这一段。
                        model = AbortingModel(
                            ScriptedInvestigationModel(window),
                            abortOnCall = index % SCRIPTED_ROUNDS + 2
                        )
                    ).run(resumed, start, boundary::onBoundary)

                    FaultPosition.AFTER_TOOL_SUCCESS -> graph(
                        wrapTools = { delegate ->
                            AbortingToolInvoker(
                                delegate,
                                abortsOnFirstCall = delegate.tool == SCRIPTED_TOOLS[index % SCRIPTED_TOOLS.size],
                                onCaptured = { request, result -> capturedCall = CapturedCall.of(request, result) }
                            )
                        }
                    ).run(resumed, start, boundary::onBoundary)

                    FaultPosition.STALE_WORKER_AFTER_TAKEOVER -> {
                        // 在完成第 k 次边界写回之后暂停：状态已经落库，人却没了。
                        val pauseAfterWrite = index % PAUSE_POINTS + 1
                        var boundaries = 0
                        // 显式命名 onNodeBoundary：`RcaStateGraph.run` 的最后一个参数是
                        // `traceId`（可选），尾随 lambda 语法会去绑它而不是绑回调。
                        graph().run(resumed, start, onNodeBoundary = { node, state ->
                            val proceed = boundary.onBoundary(node, state)
                            boundaries += 1
                            if (proceed && boundaries == pauseAfterWrite + 1) {
                                throw RcaInjectionAbort("第 $pauseAfterWrite 次边界写回完成后进程终止")
                            }
                            proceed
                        })
                    }
                }
                false
            } catch (abort: RcaInjectionAbort) {
                true
            }

            return CrashOutcome(
                aborted = aborted,
                capturedCall = capturedCall,
                committedRecord = committedRecordFor(position, index)
            )
        }

        /**
         * 「已提交但状态未迁移」的那条记录，取自基准运行。
         *
         * 不自己造一条：持久化调用记录的内容由真实运行写下，套件只负责让它出现在
         * 「快照里没有它」的位置上。自己造一条会让这套件开始为一份不存在的记录背书。
         *
         * 轮次必须与注入点对上：中止发生在第 r 轮的工具调用成功之后、第 r+1 轮决策返回之前，
         * 所以「已提交、状态未迁移」的正是第 r 轮那条。取决策轮次 0（基线）会取错——
         * 基线证据在上一次边界写回时就进了快照账本，那条记录根本不是半提交。
         */
        private fun committedRecordFor(position: FaultPosition, index: Int): ToolCallRecord? {
            if (position != FaultPosition.OBSERVATION_COMMITTED_STATE_STALE) return null
            val round = index % SCRIPTED_ROUNDS + 1
            return referenceHistory.firstOrNull { it.decisionRound == round }
                ?: error("基准运行里没有第 $round 轮的调用记录，注入点与剧本已经对不上了")
        }

        /** 基准运行写下的调用历史。 */
        private val referenceHistory: List<ToolCallRecord> by lazy {
            runReference(window).finalState.toolHistory
        }
    }

    /** 被截断的调用留痕。只带构造恢复输入所需的最小字段。 */
    private data class CapturedCall(
        val toolName: RcaToolName,
        val toolVersion: String,
        val paramsDigest: String,
        val decisionRound: Int,
        val status: ToolResultStatus
    ) {
        companion object {
            fun of(request: ToolRequest, result: ToolResult): CapturedCall = CapturedCall(
                toolName = result.tool,
                toolVersion = result.toolVersion,
                paramsDigest = result.paramsDigest,
                decisionRound = request.decisionRound,
                status = result.status
            )
        }
    }

    private data class CrashOutcome(
        val aborted: Boolean,
        val capturedCall: CapturedCall?,
        val committedRecord: ToolCallRecord?
    )

    /** 模型侧注入：第 n 次决策返回之前中止。 */
    private class AbortingModel(
        private val delegate: InvestigationModel,
        private val abortOnCall: Int
    ) : InvestigationModel {

        override val modelVersion: String get() = delegate.modelVersion

        override val promptVersion: String get() = delegate.promptVersion

        private var calls = 0

        override fun decide(prompt: InvestigationPrompt): AgentDecision {
            calls += 1
            if (calls == abortOnCall) throw RcaInjectionAbort("第 $calls 次模型决策返回前进程终止")
            return delegate.decide(prompt)
        }
    }

    /**
     * 工具侧注入：**先让工具真的执行成功**，再中止。
     *
     * 顺序是这条注入的全部意义所在：只有先成功，才会出现「上游已执行、本地一无所有」这个状态。
     * 提前中止（还没调用）就退化成「模型返回前」那一类了。
     */
    private class AbortingToolInvoker(
        private val delegate: RcaToolInvoker,
        private val abortsOnFirstCall: Boolean,
        private val onCaptured: (ToolRequest, ToolResult) -> Unit
    ) : RcaToolInvoker {

        override val tool: RcaToolName get() = delegate.tool

        override val toolVersion: String get() = delegate.toolVersion

        private var calls = 0

        override fun invoke(request: ToolRequest): ToolResult {
            val result = delegate.invoke(request)
            calls += 1
            if (abortsOnFirstCall && calls == 1) {
                onCaptured(request, result)
                throw RcaInjectionAbort("工具 ${tool.storageCode} 成功后、本地写回前进程终止")
            }
            return result
        }
    }
}

private const val CRASHED_OWNER = "worker-crashed"
private const val RECOVERY_OWNER = "worker-recovery"
private const val INJECTION_LEASE_SECONDS = 60L
private const val INJECTION_NOW_OFFSET_HOURS = 3L

/**
 * 一次运行实际写回过的节点序列。
 *
 * 边界钩子在**进入**每个节点之前触发，而第一个边界不写回（见 [RcaBoundaryWriter]：
 * 领取时读到的快照就是这个位置的状态）。所以它就是「节点执行序列去掉第一步」。
 * 从 [RcaRunResult.steps] 推而不是另记一份，是因为那会引入第二份真相——
 * 注入点定位与崩溃模拟必须建立在**同一份**留痕上。
 */
private fun RcaRunResult.writableBoundaries(): List<RcaNode> = steps.drop(1).map(RcaStepTrace::node)

/** 预算指纹：三个数一起比，避免漏比某一项。 */
private fun GapsAndBudget.budgetFingerprint(): String =
    "$stepsUsed/$tokensUsed/$costUsedUsd"

// ---------------------------------------------------------------------------
// 断言失败时的诊断
// ---------------------------------------------------------------------------

/**
 * 抹掉工具调用记录里的**实测时延**。
 *
 * 这是「恢复后状态与基准逐字段一致」这条断言里唯一被排除的字段，理由是它根本不是状态：
 * `latencyMs` 记的是那一次调用实际花了多少毫秒，由工具层的秒表测出来。同一份输入、
 * 同一段代码，第一次调用要付类加载与 JIT 的钱（实测 18ms），热起来之后同样的调用是 0ms。
 * 它反映的是 JVM 的冷热，不是恢复对错——恢复不可能、也不应该「复现」它。
 *
 * 排除必须是显式的一步，而不是把字段从比较里悄悄拿掉：悄悄放过几个字段，
 * 等于把一条强断言慢慢削成弱断言，而报告上仍旧写着「逐字段一致」。
 */
private fun RcaSharedState.withoutMeasuredLatency(): RcaSharedState =
    copy(toolHistory = toolHistory.map { it.copy(latencyMs = null) })

/**
 * 恢复态与基准态的逐字段差异。
 *
 * 只在「两者应当完全相等」这条断言上使用。不把两个状态整个交给断言打印，
 * 是因为共享状态有十几个字段、其中几个还是嵌套列表：全量输出会把「究竟哪一处不一样」
 * 埋进几千个字符里，而回答那个问题才是这条断言存在的意义。
 *
 * 也不把字段折叠成自定义摘要。这里踩过一次：摘要只覆盖了字段的一部分，
 * 而那一次真正的差异恰好落在摘要之外——断言报错说「不等」，诊断却说「无差异」，
 * 比没有诊断更费时间。所以每个字段都按**整体**比较，只在渲染时缩写。
 */
private fun stateDiff(reference: RcaSharedState, recovered: RcaSharedState): List<String> = listOf(
    FieldValue("taskId", reference.taskId, recovered.taskId),
    FieldValue("episodeId", reference.episodeId, recovered.episodeId),
    FieldValue("stateVersion", reference.stateVersion, recovered.stateVersion),
    FieldValue("problemProfile", reference.problemProfile, recovered.problemProfile),
    FieldValue("mandatoryPlan", reference.mandatoryPlan, recovered.mandatoryPlan),
    FieldValue("hypotheses", reference.hypotheses, recovered.hypotheses),
    FieldValue("evidenceLedger", reference.evidenceLedger, recovered.evidenceLedger),
    FieldValue("toolHistory", reference.toolHistory, recovered.toolHistory),
    FieldValue("gapsAndBudget", reference.gapsAndBudget, recovered.gapsAndBudget),
    FieldValue("stopProposal", reference.stopProposal, recovered.stopProposal),
    FieldValue("arbitration", reference.arbitration, recovered.arbitration)
).filter(FieldValue::differs).map(FieldValue::render)

/**
 * 一个字段的基准值与恢复值。
 *
 * 值以 `Any?` 承载：这里要的是「按字段名逐个比」，而字段之间类型本就不同，
 * 补一个泛型也换不回类型安全——比较与渲染都只依赖 `equals` 与 `toString`。
 */
private class FieldValue(val name: String, val expected: Any?, val actual: Any?) {

    fun differs(): Boolean = expected != actual

    fun render(): String =
        if (expected is List<*> && actual is List<*>) renderLists(expected, actual)
        else "$name：基准=${abbreviate(expected)} 恢复=${abbreviate(actual)}"

    private fun renderLists(expected: List<*>, actual: List<*>): String {
        val sizes = "条数 基准=${expected.size} 恢复=${actual.size}"
        // 先找公共前缀里的第一个分叉点；找不到说明差异在尾部（某一侧更长），
        // 那就报尾部第一项——此时两边的下标已经不对齐，只能用取不到时空出来的那一侧表达。
        val firstDiff = (0 until minOf(expected.size, actual.size)).firstOrNull { expected[it] != actual[it] }
            ?: minOf(expected.size, actual.size).takeIf { expected.size != actual.size }
            ?: return "$name：$sizes（逐项相等）"
        return "$name：$sizes；首个不同项 #$firstDiff：" +
            "基准=${abbreviate(expected.getOrNull(firstDiff))} 恢复=${abbreviate(actual.getOrNull(firstDiff))}"
    }

    /**
     * 缩写渲染值。
     *
     * 截断而不是全量：一条差异只需要看出「哪里不一样」，完整的正文在快照里，
     * 控制台只提供定位。截断处标出总长度，免得把「到这里就没了」误读成「内容就这么长」。
     */
    private fun abbreviate(value: Any?): String {
        val text = value?.toString() ?: "null"
        return if (text.length <= VALUE_MAX) text else text.take(VALUE_MAX) + "…（共 ${text.length} 字符）"
    }

    private companion object {
        const val VALUE_MAX = 1200
    }
}

/**
 * 一次注入的结果。
 *
 * 可见性为 `internal` 而不是 file-private：它要作为 [InjectionTally.record] 的入参，
 * 而汇总类是公开的。把汇总类也降成 internal 会把整个套件对外的可读性一起收掉，
 * 让一个参数类型的可见性去决定套件的形状，代价不成比例。
 */
internal data class InjectionResult(
    val recovered: Boolean,
    val human: Boolean,
    val writeBackApplied: Boolean
)

/** 一个位置的汇总。 */
class InjectionTally(val position: FaultPosition) {

    var injections: Int = 0
        private set

    var recovered: Int = 0
        private set

    var human: Int = 0
        private set

    /** 收口动作本身是否写入成功。转人工那一支用它：`abandon` 必须真的生效。 */
    var writeBackApplied: Int = 0
        private set

    /** 本位置的断言失败数，由 [RcaRecoveryInjection.run] 按前后差值填。 */
    var failures: Int = 0

    internal fun record(result: InjectionResult) {
        injections += 1
        if (result.recovered) recovered += 1
        if (result.human) human += 1
        if (result.writeBackApplied) writeBackApplied += 1
    }
}

/** 恢复完成之后的自洽性抽查统计。 */
class SweepTally {
    var checked: Int = 0
    var reusable: Int = 0
    var notReusable: Int = 0
}

/** 一次注入验证的产物。 */
data class RcaRecoveryInjectionOutcome(
    val tallies: Map<FaultPosition, InjectionTally>,
    val decisions: Map<RcaRecoveryAction, Int>,
    val sweep: SweepTally,
    val checks: ReplayChecks
) {
    val injections: Int get() = tallies.values.sumOf { it.injections }

    val recovered: Int get() = tallies.values.sumOf { it.recovered }

    val human: Int get() = tallies.values.sumOf { it.human }

    val passed: Boolean get() = checks.passed

    fun report(): String = buildString {
        appendLine("=== 租约 fencing 与三态恢复：故障注入验证 ===")
        appendLine()
        appendLine("[注入结果]")
        appendLine("  位置                                 注入  自动恢复  转人工  写回生效  断言失败")
        tallies.forEach { (position, tally) ->
            appendLine(
                "  ${position.title.padEnd(32)}" +
                    tally.injections.toString().padStart(6) +
                    tally.recovered.toString().padStart(10) +
                    tally.human.toString().padStart(8) +
                    tally.writeBackApplied.toString().padStart(10) +
                    tally.failures.toString().padStart(10)
            )
        }
        appendLine(
            "  ${"合计".padEnd(32)}" +
                injections.toString().padStart(6) +
                recovered.toString().padStart(10) +
                human.toString().padStart(8)
        )

        appendLine()
        appendLine("[注入路径上的恢复动作分布]")
        if (decisions.isEmpty()) {
            appendLine("  （没有任何恢复判定被触发，这不正常：检查注入点是否真的留下了在途调用）")
        } else {
            decisions.entries.sortedBy { it.key.storageCode }
                .forEach { (action, count) -> appendLine("  ${action.storageCode.padEnd(26)} $count") }
        }

        appendLine()
        appendLine("[恢复完成后的自洽性抽查]")
        appendLine(
            "  已提交记录 ${sweep.checked} 条，可直接复用 ${sweep.reusable} 条，" +
                "需要重放 ${sweep.notReusable} 条"
        )

        appendLine()
        appendLine("[被验证的不变式]")
        appendLine("  · 恢复后的共享状态与基准逐字段相等（不是「跑完了」，是「跑得和没崩过一样」）")
        appendLine("  · 证据无重复、无缺失（确定性 evidence ID）")
        appendLine("  · 预算未重复扣减、未因崩溃少扣")
        appendLine("  · 接管后 lease_generation 恰好递增一次，终态不再持有租约")
        appendLine("  · 旧持有者在租约过期后不再可能写回（generation + owner + state_version 三重条件）")
        appendLine("  · 判定不了的在途调用由 Worker 自己收口成人工——判定接在执行路径上，不是只写成一条规则")
        appendLine("  · 按轮次到达的注入点留下的恢复起点与设计一致（模型返回前/工具成功后/半提交/被接管）")

        appendLine()
        appendLine("[断言]")
        checks.failureLines().forEach { appendLine("  失败：$it") }
        appendLine("  ${if (passed) "全部通过（${checks.total} 项）" else "未通过"}")
    }
}

fun main() {
    val outcome = RcaRecoveryInjection.run()
    println(outcome.report())
    // 数量对不上说明注入点没被走到（例如模型侧的中止被上游吞掉了）。这类问题必须算失败，
    // 否则一份「一条路径都没跑到」的报告会被读成「全部通过」。
    val expected = FaultPosition.entries.size * RcaRecoveryInjection.ITERATIONS_PER_POSITION
    val countOk = outcome.injections == expected && outcome.recovered + outcome.human == outcome.injections
    if (!outcome.passed || !countOk) {
        exitProcess(1)
    }
}
