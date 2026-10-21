package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.GapsAndBudget
import com.feedback.agent.rca.domain.LocalCommitState
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaToolInvoker
import com.feedback.agent.rca.domain.RcaToolName
import com.feedback.agent.rca.domain.ToolCallRecord
import com.feedback.agent.rca.domain.ToolObservation
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.domain.ToolResult
import com.feedback.agent.rca.domain.ToolResultStatus
import com.feedback.agent.rca.runtime.PendingToolCall
import com.feedback.agent.rca.runtime.RcaToolCallJournalWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 工具调用守卫的**两阶段落库**契约：谁在什么时候被写、以及什么情况下**不**写。
 *
 * ## 为什么这些断言必须存在，而不是靠端到端跑一遍
 *
 * 写入侧最容易出的错是**顺序**与**遗漏**，而两者都不会以断言失败的形式暴露：
 *
 * - 顺序反了（先调用、后落库），一切看起来都正常，只有崩在那两次写之间时
 *   才会丢一个「上游执行了但本地没痕迹」的窗口——而那个场景在测试里永远不会自然发生；
 * - 复用与预算拒绝时多写一条，代价是调用次数虚高，同样不会让任何断言变红。
 *
 * 所以这里不验「最终有没有行」，而是验**调用序列**：用一个假的 journal 记录事件，
 * 让被测工具在 `invoke` 里回写自己看到的事件，于是「先 begin 再 invoke」成为可断言的事实。
 *
 * 数据库那一段（JSON 列的形状、`useGeneratedKeys` 回填、CHECK 约束）不在本类验证，
 * 它在 `rcaEntryTest` 的 `RcaToolCallJournalContractTest` 里对真库跑。
 */
class ToolInvocationGuardTest {

    // ---------------------------------------------------------------------
    // 真实调用：begin → invoke → commit
    // ---------------------------------------------------------------------

    @Test
    fun `真实调用按 begin-invoke-commit 的顺序落库，并把行主键回填进内存记录`() {
        val journal = RecordingJournal()
        val tool = RecordingTool(journal.events)
        val guard = ToolInvocationGuard(registryWith(tool), journal)
        val state = state()

        val outcome = guard.invoke(request(tool = RcaToolName.LOG_TRACE, digest = DIGEST), state)

        assertEquals(
            listOf("begin", "invoke", "commit"),
            journal.events,
            "落库顺序不是「先记账再调用」：崩在两次写之间会丢一个无法与「没调用过」区分的窗口"
        )
        assertTrue(outcome.consumedBudget, "真实调用应当消耗预算")
        assertNull(outcome.rejectedReason)
        assertNull(outcome.replayedFromId)

        val begun = journal.begun.single()
        assertEquals(TASK_ID, begun.taskId)
        assertEquals(RcaToolName.LOG_TRACE.storageCode, begun.toolName)
        assertEquals(TOOL_VERSION, begun.toolVersion, "tool_version 必须取注册表里的实现版本，而不是重新拼一个")
        assertEquals(ROUND, begun.decisionRound)
        assertEquals(DIGEST, begun.paramsDigest)
        assertEquals(PARAMS, begun.params, "参数快照必须是这次调用真正用的那一份")

        // 回填主键：这条记录随后会进 state.toolHistory，将来被复用时会写成 replayedFromId。
        // 留 0 会让审计指向一行不存在的记录。
        assertEquals(ROW_ID, outcome.record?.id, "落库主键没有回填进内存记录")
        assertEquals(journal.committed.single().first, ROW_ID)
        assertFalse(tool.observedWithoutPendingRow, "工具被调用时还没有 PENDING 行")
    }

    // ---------------------------------------------------------------------
    // 链路标识：只透传，不生成
    // ---------------------------------------------------------------------

    /**
     * 调用方给了链路标识时，它必须**原样**出现在落库行上，且 span 由四要素推出来。
     *
     * 断言的是「守卫没有做任何加工」：链路标识唯一要保证的性质是
     * 「同一件事在所有地方是同一个值」，守卫在这里换个写法（截断、加前缀、大小写归一）
     * 就会让库里那一行与任务行上的 trace 对不上，排障时按 trace join 会漏掉它。
     */
    @Test
    fun `调用方给的链路标识原样落库，span 由四要素推导`() {
        val journal = RecordingJournal()
        val guard = ToolInvocationGuard(registryWith(RecordingTool(journal.events)), journal)

        guard.invoke(request(tool = RcaToolName.LOG_TRACE, digest = DIGEST), state(), TRACE_ID)

        val begun = journal.begun.single()
        assertEquals(TRACE_ID, begun.traceId, "守卫改动了 trace：库里的行按 trace join 时会漏掉")
        assertEquals(
            ToolSpanIdFactory.of(TRACE_ID, RcaToolName.LOG_TRACE.storageCode, DIGEST, ROUND),
            begun.spanId,
            "span 不是由「trace + 工具 + 参数摘要 + 轮次」推出来的：" +
                "崩溃重放后同一逻辑调用会得到两个 span，去重键的语义被架空"
        )
    }

    /**
     * 没有链路标识时两列都必须为空，**不允许编一个出来**。
     *
     * 编一个的代价不是「多了一列值」，而是这个事实再也查不出来：
     * 「哪些调用真的带链路」会变成「全部」——而离线夹具与早于该列引入的历史行
     * 本来就没有链路，把它们说成有，等于伪造了可观测性。
     * 库上的 `chk_rca_tool_trace_span_pair` 是同一条不变式（两列同进同出）。
     */
    @Test
    fun `没有链路标识时两列都为空，不生成替代值`() {
        val journal = RecordingJournal()
        val guard = ToolInvocationGuard(registryWith(RecordingTool(journal.events)), journal)

        guard.invoke(request(tool = RcaToolName.LOG_TRACE, digest = DIGEST), state())

        val begun = journal.begun.single()
        assertNull(begun.traceId, "没有链路标识时 trace 被编了一个出来")
        assertNull(begun.spanId, "span 是从 trace 推出来的：没有 trace 就不该有 span")
    }

    // ---------------------------------------------------------------------
    // 不该写的两条路径
    // ---------------------------------------------------------------------

    @Test
    fun `命中可复用记录时不写记录也不调用工具`() {
        val journal = RecordingJournal()
        val tool = RecordingTool(journal.events)
        val guard = ToolInvocationGuard(registryWith(tool), journal)
        // 复用条件与 RcaToolRecovery.decideCommitted 是同一条：同工具、同摘要、
        // 已提交、摘要与摘要正文都有值。这里刻意只改其中一个条件（见下一个用例）。
        val reusable = record(id = 7L)

        val outcome = guard.invoke(
            request(tool = RcaToolName.LOG_TRACE, digest = DIGEST),
            state(history = listOf(reusable))
        )

        assertTrue(outcome.replayed)
        assertEquals(7L, outcome.replayedFromId, "复用应当指向被复用的那一行的主键")
        assertEquals(emptyList<String>(), journal.events, "复用不产生新记录：调用次数会因此虚高")
        assertEquals(0, tool.calls, "复用时不得再打一次上游")
    }

    @Test
    fun `预算耗尽时既不写记录也不调用工具`() {
        val journal = RecordingJournal()
        val tool = RecordingTool(journal.events)
        val guard = ToolInvocationGuard(registryWith(tool), journal)

        val outcome = guard.invoke(
            request(tool = RcaToolName.LOG_TRACE, digest = DIGEST),
            state(budget = 0)
        )

        assertTrue(outcome.rejected)
        assertNotNull(outcome.rejectedReason)
        assertEquals(emptyList<String>(), journal.events, "被预算拒绝的调用不是一次取证，不该留下记录")
        assertEquals(0, tool.calls)
    }

    // ---------------------------------------------------------------------
    // 没接记录表的部署：行为必须与加端口之前完全一致
    // ---------------------------------------------------------------------

    @Test
    fun `未注入 journal 时行为与加端口之前一致：不落库，记录主键留零`() {
        // 这个用例本来就该没有 journal，事件列表因此只服务于「工具被调用了吗」。
        val tool = RecordingTool(mutableListOf())
        val guard = ToolInvocationGuard(registryWith(tool))

        val outcome = guard.invoke(request(tool = RcaToolName.LOG_TRACE, digest = DIGEST), state())

        assertEquals(1, tool.calls)
        assertEquals(0L, outcome.record?.id, "没有记录表时行主键必须是 0，而不是一个编出来的值")
    }

    // ---------------------------------------------------------------------
    // 测试构件
    // ---------------------------------------------------------------------

    /** 记录事件序列的假 journal。事件是字符串列表，顺序断言因此可以直接写出来。 */
    private class RecordingJournal : RcaToolCallJournalWriter {

        val events = mutableListOf<String>()
        val begun = mutableListOf<PendingToolCall>()
        val committed = mutableListOf<Pair<Long, ToolCallRecord>>()

        override fun begin(call: PendingToolCall): Long {
            events += "begin"
            begun += call
            return ROW_ID
        }

        override fun commit(rowId: Long, record: ToolCallRecord, result: ToolResult) {
            events += "commit"
            committed += rowId to record
        }
    }

    /**
     * 假的只读工具。
     *
     * 它读的是**守卫自己那个 journal 的事件列表**——
     * 「守卫有没有先落库再调用」只有从被调用方的视角才验得了：
     * 从外面看，两次写和一次调用谁先谁后都能凑出同样的终态。
     */
    private class RecordingTool(
        private val events: MutableList<String>,
        override val tool: RcaToolName = RcaToolName.LOG_TRACE
    ) : RcaToolInvoker {

        override val toolVersion: String = TOOL_VERSION

        var calls = 0

        /** 工具被调用时，守卫是否还没有落 PENDING 行。 */
        var observedWithoutPendingRow: Boolean = false

        override fun invoke(request: ToolRequest): ToolResult {
            calls += 1
            observedWithoutPendingRow = !events.contains("begin")
            events += "invoke"
            return ToolResult(
                tool = tool,
                toolVersion = toolVersion,
                status = ToolResultStatus.SUCCESS_WITH_DATA,
                paramsDigest = request.paramsDigest,
                decisionRound = request.decisionRound,
                observation = ToolObservation(
                    summary = "首错点：2026-09-28T10:00:00.000 service=pay",
                    sourceRef = "logtrace://episode/$EPISODE_ID"
                ),
                observationDigest = OBSERVATION_DIGEST
            )
        }
    }

    /**
     * 七个工具全给的注册表，只有被测的那一个是真的。
     *
     * 必须给全：[RcaToolRegistry] 构造时校验覆盖全部 [RcaToolName]，
     * 漏一个就是启动失败——这正是它设计上要的效果，测试不能绕过。
     */
    private fun registryWith(tool: RcaToolInvoker): RcaToolRegistry = RcaToolRegistry(
        RcaToolName.entries.map { name -> if (name == tool.tool) tool else StubTool(name) }
    )

    private class StubTool(override val tool: RcaToolName) : RcaToolInvoker {

        override val toolVersion: String = TOOL_VERSION

        override fun invoke(request: ToolRequest): ToolResult = error("本用例不应调用 ${tool.storageCode}")
    }

    private fun request(tool: RcaToolName, digest: String): ToolRequest = ToolRequest(
        tool = tool,
        params = PARAMS,
        decisionRound = ROUND,
        selectionReason = "用例构造",
        paramsDigest = digest
    )

    /** 一条满足复用它全部条件的记录：同工具、同摘要、已提交、摘要与正文都有值。 */
    private fun record(id: Long): ToolCallRecord = ToolCallRecord(
        id = id,
        toolName = RcaToolName.LOG_TRACE.storageCode,
        toolVersion = TOOL_VERSION,
        paramsDigest = DIGEST,
        decisionRound = ROUND - 1,
        status = ToolResultStatus.SUCCESS_WITH_DATA,
        localCommitState = LocalCommitState.COMMITTED,
        observationDigest = OBSERVATION_DIGEST,
        observationSummary = "首错点：2026-09-28T10:00:00.000 service=pay"
    )

    private fun state(
        history: List<ToolCallRecord> = emptyList(),
        budget: Int = STEP_BUDGET
    ): RcaSharedState = RcaSharedState.seeded(TASK_ID, EPISODE_ID, NOW).copy(
        gapsAndBudget = GapsAndBudget(
            stepBudget = budget,
            stepsUsed = 0,
            tokenBudget = 60_000,
            tokensUsed = 0,
            costBudgetUsd = BigDecimal("0.5"),
            costUsedUsd = BigDecimal.ZERO,
            deadlineAt = null,
            now = NOW
        ),
        toolHistory = history
    )

    private companion object {
        const val TASK_ID = 42L
        const val EPISODE_ID = 4242L
        const val ROW_ID = 100L
        const val ROUND = 2
        const val STEP_BUDGET = 12
        const val TOOL_VERSION = "9.9.9"
        const val DIGEST = "c0ffee000000000000000000000000000000000000000000000000000000beef"

        /** 32 位小写十六进制，与 `chk_rca_agent_trace` 的格式一致。 */
        const val TRACE_ID = "0123456789abcdef0123456789abcdef"
        const val OBSERVATION_DIGEST = "d00d0000000000000000000000000000000000000000000000000000000000ff"
        val PARAMS: Map<String, Any?> = linkedMapOf(
            "logId" to "log-1",
            "windowFrom" to "2026-09-28T09:30:00.000",
            "windowTo" to "2026-09-28T10:30:00.000"
        )
        val NOW: LocalDateTime = LocalDateTime.of(2026, 9, 28, 10, 30)
    }
}
