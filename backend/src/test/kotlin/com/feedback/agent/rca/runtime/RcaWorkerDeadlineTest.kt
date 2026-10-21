package com.feedback.agent.rca.runtime

import com.feedback.agent.rca.domain.LocalCommitState
import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus
import com.feedback.agent.rca.domain.ToolCallRecord
import com.feedback.agent.rca.domain.ToolResultStatus
import com.feedback.agent.rca.graph.RcaNodeContext
import com.feedback.agent.rca.graph.RcaNodeHandler
import com.feedback.agent.rca.graph.RcaNodeOutcome
import com.feedback.agent.rca.graph.RcaStateGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 超期任务的处置：**领取后不进图，直接收口**。
 *
 * ## 为什么这一条需要一个专门的用例，而不是靠端到端跑一遍
 *
 * 端到端跑不出来。超期任务的可见现象是「任务停在 `RETRY_PENDING` 上再也不动」，
 * 而那与「队列空了」在测试里长得一样：两种情况都不会抛异常、不会写错误码，
 * 区别只在于**三十天后**有没有人来收口。唯一的办法是把截止时间直接构造到过去，
 * 再断言 Worker 这一次做了什么、没做什么。
 *
 * ## 三条被断言的「没做什么」比「做了什么」更重要
 *
 * 1. **没有进图**：`contextBuild.entered == 0`。超期任务照样走完五个节点是会写库的——
 *    它会产出证据、写归因、把预算再花一遍，而这一切都发生在一个已经没有预算的任务上。
 * 2. **没有解密快照**：这一条只能由顺序保证，见最后一个用例（恢复判定同样被跳过）。
 * 3. **收口之后不再是候选**：`claim` 返回 `NoCandidate`。少这一条，
 *    收口就成了「每轮调度都领一次、每次都收口一次」的循环。
 *
 * ## 与 SQL 的分工
 *
 * 本类只驱动 [RcaClaimedTask.deadlineRemainingMs] 这一个输入。**那个差值是不是真由数据库算出**、
 * `deadline_at` 是不是真在领取时被写上，由 `rcaEntryTest` 的 `RcaTaskLeaseContractTest`
 * 对着真实 MySQL 3307 验。本机内存桩里 `enqueue(deadlineAt = ...)` 就是注入它的方式
 * （内存实现不自动补截止时间，理由写在该类的 KDoc 里）。
 */
class RcaWorkerDeadlineTest {

    // ---------------------------------------------------------------------
    // 超期：不进图，直接收口
    // ---------------------------------------------------------------------

    @Test
    fun `领取时已超期且没有快照的任务不进图，收口为转人工`() {
        val fixture = Fixture()
        // 恰好等于当前时刻：边界取「已用完」而不是「还剩 0」，见下面 `刚好到期也算超期` 的用例。
        val taskId = fixture.enqueue(deadlineAt = fixture.now)
        val graph = fixture.graph()

        val run = fixture.worker(graph).runOnce()

        val abandoned = run as RcaWorkerRun.Abandoned
        assertEquals(
            RcaTaskStatus.NEEDS_HUMAN, abandoned.status,
            "没有快照（一条证据都没有）却收口成 PARTIAL：那等于宣称「有可用证据但覆盖不足」，而证据不存在"
        )
        assertEquals(RcaStopReason.DEADLINE_EXCEEDED, abandoned.stopReason)
        assertEquals(
            "RCA_DEADLINE_EXCEEDED", abandoned.errorCode,
            "错误码与图内超期（同一 stop_reason）必须能分辨：写死字面量而不是引用常量，是为了盯住落库的那个值"
        )
        assertEquals(RcaWriteBackResult.APPLIED, abandoned.writeBack)
        assertEquals(0, fixture.contextBuild.entered, "超期任务进了图：它会照常取证、写归因、再花一遍预算")

        val row = fixture.row(taskId)
        assertEquals(RcaTaskStatus.NEEDS_HUMAN, row.taskStatus)
        assertEquals(RcaStopReason.DEADLINE_EXCEEDED, row.stopReason)
        assertNull(row.leaseOwner, "收口后仍持有租约：任务既已终态，持有者就成了一个过期的答案")
        assertNull(row.leaseUntil)
        assertTrue(row.completedAt != null, "收口没有写完成时间")
        assertNull(row.stateSnapshot)

        // 收口之后不能再是候选，否则每轮调度都会「领一次、收口一次」地空转。
        assertEquals(
            RcaClaimOutcome.NoCandidate, fixture.leases.claim("someone-else", LEASE_SECONDS),
            "已收口的任务仍在候选里"
        )
    }

    @Test
    fun `有快照的超期任务收口为部分完成，且上一轮的证据快照被保留`() {
        val fixture = Fixture()
        val taskId = fixture.enqueue(deadlineAt = fixture.now.plusMinutes(TIMEOUT_MINUTES))

        // 上一轮：领取 → 走到基线节点之后让出（快照已落库，租约未清）。
        val claim = fixture.leases.claim("first-owner", LEASE_SECONDS) as RcaClaimOutcome.Claimed
        assertEquals(
            RcaWriteBackResult.APPLIED,
            fixture.leases.writeBack(
                claim.token,
                RcaTaskMutation(
                    nextNode = RcaNode.BASELINE_EVIDENCE,
                    taskStatus = RcaTaskStatus.RUNNING,
                    stopReason = null,
                    stateSnapshot = PARTIAL_SNAPSHOT,
                    snapshotSha256 = SHA256,
                    stepsUsed = 1,
                    tokensUsed = 10,
                    costUsedUsd = BigDecimal.ZERO
                )
            )
        )

        // 租约过期且截止时间已过——生产上这是「Worker 被杀，30 分钟内没人再领」。
        fixture.databaseNow = fixture.databaseNow.plusMinutes(TIMEOUT_MINUTES + 1)

        val run = fixture.worker(fixture.graph()).runOnce()

        val abandoned = run as RcaWorkerRun.Abandoned
        assertEquals(
            RcaTaskStatus.PARTIAL, abandoned.status,
            "有快照却说「一条证据都没有」：图内 DEADLINE_EXCEEDED 同样判 PARTIAL（预算耗尽属于覆盖不足）"
        )
        assertEquals(RcaStopReason.DEADLINE_EXCEEDED, abandoned.stopReason)
        assertEquals(0, fixture.contextBuild.entered)

        val row = fixture.row(taskId)
        assertEquals(
            PARTIAL_SNAPSHOT, row.stateSnapshot,
            "收口把上一轮的快照抹掉了：`保留已有证据` 这句话就不成立，PARTIAL 也就没有依据"
        )
        assertEquals(RcaTaskStatus.PARTIAL, row.taskStatus)
    }

    @Test
    fun `刚好到期也算超期`() {
        val fixture = Fixture()
        fixture.enqueue(deadlineAt = fixture.now)
        val graph = fixture.graph()

        val run = fixture.worker(graph).runOnce()

        assertTrue(
            run is RcaWorkerRun.Abandoned,
            "剩余 0 毫秒被判成「还有预算」：边界会因此取决于毫秒级的时间差，而不是一条可复述的规则"
        )
        assertEquals(0, fixture.contextBuild.entered)
    }

    // ---------------------------------------------------------------------
    // 未超期：行为必须与加这条规则之前完全一致
    // ---------------------------------------------------------------------

    @Test
    fun `未超期的任务照常进图`() {
        val fixture = Fixture()
        fixture.enqueue(deadlineAt = fixture.now.plusMinutes(TIMEOUT_MINUTES))
        val graph = fixture.graph()

        val run = fixture.worker(graph).runOnce()

        val finished = run as RcaWorkerRun.Finished
        assertEquals(RcaTaskStatus.COMPLETED, finished.status)
        assertEquals(1, fixture.contextBuild.entered, "未超期的任务没有进图")
    }

    @Test
    fun `没有截止时间的任务照常进图`() {
        // 这一条是回归保护：离线回放与故障注入套件里的任务全都没有截止时间
        // （`InMemoryRcaTaskLeasePort.enqueue` 的默认值），它们的行为必须一个字都不变。
        val fixture = Fixture()
        fixture.enqueue(deadlineAt = null)
        val graph = fixture.graph()

        val run = fixture.worker(graph).runOnce()

        assertTrue(run is RcaWorkerRun.Finished, "没有截止时间的任务被当成了超期")
        assertEquals(1, fixture.contextBuild.entered)
    }

    // ---------------------------------------------------------------------
    // 顺序：超期判定在恢复判定之前
    // ---------------------------------------------------------------------

    @Test
    fun `超期判定排在恢复判定之前，判定不了的调用不会被拿去问上游`() {
        val fixture = Fixture()
        fixture.enqueue(deadlineAt = fixture.now.minusSeconds(1))
        val graph = fixture.graph()

        // 这条记录单看会让恢复判定给出「转人工（RCA_RECOVERY_UNRESOLVED）」——
        // 本用例要断言的是：它根本没有被问到。
        val blocking = RcaRecoveryResolver(
            journal = RcaToolCallJournalPort { listOf(unknownRecord()) },
            provider = ProviderStateQueryPort { error("超期任务不该走到对账这一步") }
        )

        val run = fixture.worker(graph, blocking).runOnce()

        val abandoned = run as RcaWorkerRun.Abandoned
        assertEquals(
            "RCA_DEADLINE_EXCEEDED", abandoned.errorCode,
            "恢复判定先跑了：超期任务在「能不能重放」这个问题上花掉了几步才被收口，" +
                "而对账口将来是一次网络调用"
        )
        assertEquals(0, fixture.contextBuild.entered)
    }

    // ---------------------------------------------------------------------
    // 链路标识：从任务行搬到节点上下文，且只在任务行上有
    // ---------------------------------------------------------------------

    /**
     * 「Worker → 状态图 → 节点上下文」这一段只有一行默认参数，所以它值得一条直接断言。
     *
     * 这一行漏掉的代价不会以异常的形式出现：Worker 照样跑完、任务照样收口，
     * 只是工具调用行上挂的 trace 与任务行上的不是同一个，或者干脆是空的——
     * 而**排障时按 trace 串一次执行的调用链**正是这两列唯一要支撑的事。
     * 端到端跑不出来（上游降级时任务根本不会发起工具调用，见 `RcaWorkerEndToEndTest`），
     * 只能在这里把节点看到的值直接断言出来。
     */
    @Test
    fun `任务行上的链路标识原样传进节点上下文`() {
        val fixture = Fixture()
        fixture.enqueue(deadlineAt = null, traceId = TRACE_ID)
        val graph = fixture.graph()

        fixture.worker(graph).runOnce()

        assertEquals(
            listOf(TRACE_ID), fixture.contextBuild.observedTraceIds,
            "节点看到的链路标识与任务行上的不是同一个：工具调用行会挂到另一条链路上"
        )
    }

    @Test
    fun `任务行没有链路标识时节点上下文也是 null，Worker 不自己生成`() {
        val fixture = Fixture()
        // 离线套件与早于该列引入的历史行都是这个形状（`trace_id` 为 NULL）。
        fixture.enqueue(deadlineAt = null, traceId = null)
        val graph = fixture.graph()

        fixture.worker(graph).runOnce()

        assertEquals(
            listOf<String?>(null), fixture.contextBuild.observedTraceIds,
            "Worker 凭空生成了一份链路标识：库里那一行仍然是 NULL，" +
                "于是「哪些执行真的有链路」会得到两个互相矛盾的答案，而工具调用行上的那一个无法被核实"
        )
    }

    // ---------------------------------------------------------------------
    // 测试构件
    // ---------------------------------------------------------------------

    /** 一次用例的装配。每个用例一套：租约表带状态，复用会让上一例的残留影响下一例。 */
    private class Fixture {

        /** 数据库时钟。超期与否由它裁定，不由 Worker 的墙钟——真实系统里这是 `NOW(3)`。 */
        var databaseNow: LocalDateTime = LocalDateTime.of(2026, 9, 29, 10, 0)

        val leases = InMemoryRcaTaskLeasePort { databaseNow }

        val contextBuild = RecordingNode()

        val now: LocalDateTime get() = databaseNow

        fun enqueue(deadlineAt: LocalDateTime?, traceId: String? = null): Long =
            leases.enqueue(TASK_KEY, EPISODE_ID, deadlineAt = deadlineAt, traceId = traceId)

        fun row(taskId: Long): RcaTaskRow = leases.row(taskId) ?: error("任务 $taskId 不在租约表里")

        fun worker(
            graph: RcaStateGraph,
            recovery: RcaRecoveryResolver = RcaRecoveryResolver.NONE
        ): RcaWorker = RcaWorker(
            leases = leases,
            graph = graph,
            codec = RcaSnapshotJsonCodec(),
            clock = RcaClock.fixed(databaseNow),
            workerId = OWNER,
            leaseSeconds = LEASE_SECONDS,
            recovery = recovery
        )

        /**
         * 五个节点全给（[RcaStateGraph] 构造时校验覆盖），只有第一个是真的。
         * 其余四个一旦被执行就炸——本类要断言的是「压根没进图」，
         * 如果一个都没进却仍然走到后面，那就是路由错了，必须显性失败。
         */
        fun graph(): RcaStateGraph = RcaStateGraph(
            listOf(
                contextBuild,
                UnreachableNode(RcaNode.BASELINE_EVIDENCE),
                UnreachableNode(RcaNode.AGENT_INVESTIGATE),
                UnreachableNode(RcaNode.ROOT_CAUSE_ARBITRATE),
                UnreachableNode(RcaNode.RCA_PERSIST)
            )
        )
    }

    /** 记录自己被进入过几次，并直接把任务收口成终态。 */
    private class RecordingNode(
        override val node: RcaNode = RcaNode.CONTEXT_BUILD
    ) : RcaNodeHandler {

        var entered = 0

        /**
         * 每次进入时看到的链路标识，按进入顺序。
         *
         * 用列表而不是「一个可空字段」：后者分不出「节点看到的是 null」与「节点压根没被进入」——
         * 而本类里「没进图」是一个真实存在的结果（超期路径），
         * 那种情况下 `assertNull` 会以「通过」的形式掩盖一个没被验证的事实。
         * 空列表与 `listOf(null)` 是两件不同的事，这里必须能分辨。
         */
        val observedTraceIds = mutableListOf<String?>()

        override fun execute(context: RcaNodeContext): RcaNodeOutcome {
            entered += 1
            observedTraceIds += context.traceId
            return RcaNodeOutcome(
                state = context.state,
                next = null,
                enterReason = "用例构造：进入即收口",
                leaveReason = "用例构造的终态，不执行任何真实逻辑",
                terminalStatus = RcaTaskStatus.COMPLETED,
                stopReason = RcaStopReason.EVIDENCE_SUFFICIENT
            )
        }
    }

    private class UnreachableNode(override val node: RcaNode) : RcaNodeHandler {

        override fun execute(context: RcaNodeContext): RcaNodeOutcome =
            error("本用例不应进入节点 ${node.storageCode}")
    }

    /** 库上写着「认不出的落库状态」，单看会让恢复判定转人工。 */
    private fun unknownRecord(): ToolCallRecord = ToolCallRecord(
        toolName = "LOG_TRACE",
        toolVersion = "1.0.0",
        paramsDigest = DIGEST,
        decisionRound = 1,
        status = ToolResultStatus.UNKNOWN,
        localCommitState = LocalCommitState.UNKNOWN
    )

    private companion object {
        const val TASK_KEY = "rca-deadline-test"
        const val EPISODE_ID = 7L
        const val OWNER = "deadline-test-worker"
        const val LEASE_SECONDS = 60L
        const val TIMEOUT_MINUTES = 30L
        const val DIGEST = "c0ffee000000000000000000000000000000000000000000000000000000beef"
        /** 32 位小写十六进制，与 `chk_rca_agent_trace` 的格式一致。 */
        const val TRACE_ID = "0123456789abcdef0123456789abcdef"
        const val PARTIAL_SNAPSHOT = """{"case":"上一轮走到基线节点之后被杀"}"""
        const val SHA256 = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    }
}
