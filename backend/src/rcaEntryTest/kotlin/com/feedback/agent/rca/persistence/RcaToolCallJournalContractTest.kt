package com.feedback.agent.rca.persistence

import com.feedback.agent.rca.domain.EvidenceCoverage
import com.feedback.agent.rca.domain.EvidenceEntry
import com.feedback.agent.rca.domain.EvidenceType
import com.feedback.agent.rca.domain.GapsAndBudget
import com.feedback.agent.rca.domain.LocalCommitState
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaToolName
import com.feedback.agent.rca.domain.ToolCallRecord
import com.feedback.agent.rca.domain.ToolObservation
import com.feedback.agent.rca.domain.ToolResult
import com.feedback.agent.rca.domain.ToolResultStatus
import com.feedback.agent.rca.runtime.PendingToolCall
import com.feedback.agent.rca.runtime.RcaRecoveryOutcome
import com.feedback.agent.rca.runtime.RcaRecoveryResolver
import com.feedback.agent.rca.runtime.RcaToolCallJournalPort
import com.feedback.agent.rca.runtime.RcaToolCallJournalWriter
import com.feedback.agent.rca.runtime.RcaToolRecovery
import com.feedback.agent.rca.runtime.RcaRecoveryAction
import com.feedback.agent.rca.tools.RcaCanonicalDigest
import com.feedback.repository.impl.rca.RcaProviderStateUnavailableRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * `t_rca_tool_call` 读写两侧的契约验证，跑在真的 MySQL 3307 上。
 *
 * ## 为什么必须有一个连真库的测试
 *
 * 写入侧是本次改动新加的，它此前**一行都没跑过**。而这份 SQL 里有三处只有在真库上
 * 才暴露得出来的东西：`params_snapshot` / `observation_snapshot` 是 JSON 列（写非法 JSON
 * 会整条语句失败）、主键靠 `useGeneratedKeys` 回填、`chk_rca_tool_dedup_digest` 与
 * `chk_rca_tool_result_status` 是库上的 CHECK。在本机用内存桩全绿，与「能落库」是两件事。
 *
 * ## 本类要证的不是「有行」，而是**三态判定真的接通了**
 *
 * 判定表（`RcaToolRecovery`）此前没有任何写入侧，所以它只能由离线注入套件驱动。
 * 本类把「落库的行」直接喂回判定，验三行最容易出错、也最要紧的：
 *
 * | 落库状态 | 期望处置 |
 * |---|---|
 * | `PENDING`、无上游请求 ID | 可重放（只读工具重放安全） |
 * | `COMMITTED` + 有摘要，证据**不在**账本 | 重放（半提交，补齐账本） |
 * | `COMMITTED` + 有摘要，证据**在**账本 | 复用（不重复取证、不重复计费） |
 *
 * 第三行是靠 `listByTask` 里从 `observation_snapshot` 抽 `$.summary` 支撑的：
 * 抽不出来就永远判成半提交，于是每次重启都会把已经查过的东西再查一遍。
 * 也就是说这一条断言验的是「摘要真的跨进程活下来了」，不是一句形式上的字段比对。
 *
 * ## 形状上的约定与 [RcaPersistenceContractTest] 保持一致
 *
 * 整个类在会回滚的事务里跑，因此不需要清理、也不需要自己造父行（借用一行真实任务的
 * `(id, episode_id)`）。**唯一的例外是 `未识别的落库编码` 那一条：它用一次原始 INSERT
 * 造出一个生产写入侧永远不会写出的状态**（`local_commit_state='UNKNOWN'`）——
 * 那条分支要验的正是「读侧遇到它时怎么兜底」，没有别的办法产出来。
 * 该行同样随事务回滚，且落在同一个借用任务上。
 */
@EnabledIfEnvironmentVariable(named = "RCA_ENTRY_TEST_ENABLED", matches = "true")
@SpringBootTest(
    properties = [
        "xxl.job.enabled=false",
        "spring.flyway.enabled=false",
        "spring.main.lazy-initialization=true",
        "app.rca.intake-enabled=false",
        "app.rca.worker-enabled=false",
        // 与持久化契约测试同一理由：万一将来有人在这里加了需要模型的断言，
        // 应当是显式失败，而不是悄悄用本机密钥发起一次真实调用。
        "app.ai.llm.api-key=",
        "app.ai.llm.model="
    ]
)
@Transactional
class RcaToolCallJournalContractTest @Autowired constructor(
    private val journal: RcaToolCallJournalPort,
    private val writer: RcaToolCallJournalWriter,
    /**
     * 装配层注入的恢复判定。它**不是**被直接断言的对象，
     * 用来证明 `RcaRuntimeConfiguration` 里那个 Bean 接的是真实记录表。
     */
    private val recovery: RcaRecoveryResolver,
    private val jdbc: JdbcTemplate,
    @Value("\${spring.datasource.url}") private val datasourceUrl: String
) {

    /** 本次运行的唯一标记，只用在证据 ID 上：`evidence_id` 上没有唯一键，不会跨运行耦合。 */
    private val marker: String = "itest-" + UUID.randomUUID().toString().substring(0, 8)

    /**
     * 参数摘要取**真的**规范摘要，而不是随手编一个 64 位十六进制。
     *
     * 编一个的代价是：`params_digest` 与 `params_snapshot` 之间那句「拿到一行记录的人
     * 可以只凭快照核对摘要」就失去了可断言的形式——两边本来就对不上，断言只能退化成
     * 「字段非空」。用真摘要之后，这条断言变成「快照、摘要、参数三者一致」，
     * 而那正是 `params_snapshot` 这一列存在的理由。
     *
     * 也满足 `chk_rca_tool_dedup_digest` 的 `^[0-9a-f]{64}$`。
     */
    private val digest: String = RcaCanonicalDigest.ofParams(PARAMS)

    private val evidenceId: String = "EV-LOG_TRACE-" + marker.takeLast(8)

    // ---------------------------------------------------------------------
    // 阶段一：发起
    // ---------------------------------------------------------------------

    @Test
    fun `发起行落库为未落定，且参数快照能反推出参数摘要`() {
        assertLocalPort()
        val taskId = fixtureTaskId()

        val rowId = writer.begin(pendingCall(taskId))

        val row = toolCallRow(taskId)
        assertEquals(rowId, (row["id"] as Number).toLong(), "useGeneratedKeys 没有把主键回填进 PO")
        assertEquals("PENDING", row["local_commit_state"])
        assertEquals(
            ToolResultStatus.UNKNOWN.storageCode, row["result_status"],
            "发起阶段只能写 UNKNOWN：chk_rca_tool_result_status 的取值集合里没有「进行中」"
        )
        assertNull(
            row["provider_request_id"],
            "发起阶段编一个上游请求 ID，会让「无从对账」在恢复判定里变成「可以按 ID 对账」"
        )
        assertEquals(PARAMS["logId"], firstSnapshotValue(row, "logId"))
        assertEquals(
            digest, row["params_digest"],
            "落库的摘要与调用方给的不是同一个：这一列必须原样存下判定所用的那个摘要"
        )

        // 快照必须能反推摘要：这是「拿到一行记录的人可以核对它就是那次取证」的依据。
        // 做不到的话，params_snapshot 就只是一段没人能验证来源的 JSON。
        // 这一条同时验了类型没有在 JSON 往返里变掉（PARAMS 里刻意混了字符串与数字）。
        @Suppress("UNCHECKED_CAST")
        val parsed = ObjectMapper().readValue(row["params_snapshot"] as String, Map::class.java) as Map<String, Any?>
        assertEquals(
            digest, RcaCanonicalDigest.ofParams(parsed),
            "参数快照与 params_digest 对不上：快照的形状必须与摘要所依据的规范形式一致"
        )
    }

    @Test
    fun `同一轮次重复发起复用同一行，不会插出第二条`() {
        assertLocalPort()
        val taskId = fixtureTaskId()

        val first = writer.begin(pendingCall(taskId))
        val second = writer.begin(pendingCall(taskId))

        assertEquals(first, second, "崩溃恢复重跑同一节点时新插了一条，恢复判定会看到两条 PENDING 各重放一次")
        assertEquals(1L, countToolCalls(taskId), "同一 (任务, 工具, 摘要, 轮次) 只允许有一条未落定行")

        // 已提交的行不能被沿用：沿用会覆盖一条已经产生过证据的结论。
        writer.commit(first, committedRecord(), observation(digest))
        val third = writer.begin(pendingCall(taskId))
        assertTrue(third != first, "已提交的行被当成可沿用的 PENDING 行复用了")
        assertEquals(2L, countToolCalls(taskId))
    }

    // ---------------------------------------------------------------------
    // 阶段二：回写，以及读回来的形状
    // ---------------------------------------------------------------------

    @Test
    fun `回写把行推到已提交，摘要与证据 ID 跨进程可读回`() {
        assertLocalPort()
        val taskId = fixtureTaskId()

        val rowId = writer.begin(pendingCall(taskId))
        writer.commit(rowId, committedRecord(), observation(digest))

        val row = toolCallRow(taskId)
        assertEquals("COMMITTED", row["local_commit_state"])
        assertEquals(ToolResultStatus.SUCCESS_WITH_DATA.storageCode, row["result_status"])
        assertEquals(evidenceId, row["evidence_id"])
        assertEquals(412, (row["latency_ms"] as Number).toInt())
        assertEquals(0, BigDecimal("0.012345").compareTo(row["cost_usd"] as BigDecimal))

        // 读侧的字段对应关系。observationSummary 这一条是重点：它来自 SQL 对 JSON 列的抽取，
        // 抽不出来时读侧会判成「已落定的失败」，于是每次重启都重放一次。
        val record = journal.recordsOf(taskId).single { it.paramsDigest == digest }
        assertEquals(LocalCommitState.COMMITTED, record.localCommitState)
        assertEquals(ToolResultStatus.SUCCESS_WITH_DATA, record.status)
        assertEquals(SUMMARY, record.observationSummary, "观察摘要没有从 JSON 列里抽出来")
        assertEquals(listOf(evidenceId), record.evidenceIds)
        assertEquals(ROW_ROUND, record.decisionRound)
        assertEquals(TOOL_VERSION, record.toolVersion)
        assertEquals(412, record.latencyMs)
        assertEquals(0, BigDecimal("0.012345").compareTo(record.costUsd!!))
    }

    // ---------------------------------------------------------------------
    // 链路标识列：写得进去，且库上的不变式真的在
    // ---------------------------------------------------------------------

    /**
     * 这一条只验**最外层的搬运**：调用方给的两列原样落进真库的列。
     *
     * 其余几段由别的套件分工验证，这里不重复：
     *
     * | 环节 | 由谁验证 |
     * |---|---|
     * | `claim` 在任务行上补出 32 位小写十六进制且只补一次 | `RcaTaskLeaseContractTest` |
     * | 守卫把节点给的 trace 透传进 `PendingToolCall`，并推导 span | `ToolInvocationGuardTest` |
     * | span 的确定性、形状、以及「没有 trace 就没有 span」 | `ToolSpanIdFactoryTest` |
     * | Worker 把任务行上的 trace 搬进节点上下文 | `RcaWorkerDeadlineTest` |
     * | 两列真的写进 MySQL 的列，且库上的 CHECK 拦得住非法行 | 本方法 |
     *
     * 最后一行**必须**对着真库验：`trace_id` 是 `CHAR(32)`、`span_id` 是 `CHAR(16)`，
     * 写超长值在严格模式下会整条语句失败（而失败点在 `begin`，那时工具还没发出去）；
     * 而 `chk_rca_tool_trace_span_pair` 是一条**只在库上**的不变式，
     * 内存桩复现不了它（`InMemoryRcaTaskLeasePort` 只复现条件、不复现约束）。
     */
    @Test
    fun `链路标识与 span 落进真库的列`() {
        assertLocalPort()
        val taskId = fixtureTaskId()

        writer.begin(pendingCall(taskId).copy(traceId = TRACE_ID, spanId = SPAN_ID))

        val row = toolCallRow(taskId)
        assertEquals(
            TRACE_ID, row["trace_id"],
            "链路标识没有落进列里：工具调用行与任务行在事后无法按 trace 关联"
        )
        assertEquals(SPAN_ID, row["span_id"])
    }

    @Test
    fun `没有链路标识时两列都为空，不会只落其中一个`() {
        assertLocalPort()
        val taskId = fixtureTaskId()

        // pendingCall 的默认形状：traceId / spanId 都为 null（离线夹具与历史行如此）。
        writer.begin(pendingCall(taskId))

        val row = toolCallRow(taskId)
        assertNull(row["trace_id"], "没有链路标识时 trace 列被写上了值")
        assertNull(row["span_id"], "span 是从 trace 推出来的：没有 trace 就不该有 span")
    }

    /**
     * 只写 span 不写 trace 的行必须被库拒掉。
     *
     * 写入侧（`ToolSpanIdFactory`）本来就返回「要么两个都有、要么两个都没有」，
     * 所以这条用例**不是**在验写入侧——它验的是**库里真有这道约束**。
     * 缺了它，「有 span 没 trace」这种无法解释的行可以被将来任何一条新的写入路径写进去，
     * 而那种行既不能被任何一条链路捞出来，又让「这次调用有没有链路标识」没有干净答案。
     *
     * 与 `未识别的落库编码` 那一条同理，这里也只能用一次原始 INSERT：
     * 生产写入侧产不出这个形状，而这一条要验的恰恰是「它产不出来时库会怎么样」。
     */
    @Test
    fun `只有 span 没有 trace 的行被库拒掉`() {
        assertLocalPort()
        val taskId = fixtureTaskId()

        val thrown = assertThrows(Exception::class.java) {
            jdbc.update(
                """
                INSERT INTO t_rca_tool_call
                  (task_id,decision_round,tool_name,tool_version,params_digest,
                   result_status,local_commit_state,span_id)
                VALUES (?,?,?,?,?,'UNKNOWN','PENDING',?)
                """.trimIndent(),
                taskId, ROW_ROUND, RcaToolName.LOG_TRACE.storageCode, TOOL_VERSION, loneSpanDigest(),
                SPAN_ID
            )
        }
        // 断言消息里带约束名：只说「抛异常了」时，任何一条别的约束（或另一个写错的值）
        // 都会让这条用例假绿，而它要证明的正是**这一条**约束存在。
        val messages = generateSequence(thrown as Throwable?) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(
            messages.any { it.contains("chk_rca_tool_trace_span_pair") },
            "只写 span 的行没有被 chk_rca_tool_trace_span_pair 拒掉，实际异常：$messages"
        )
    }

    // ---------------------------------------------------------------------
    // 落库的行喂回判定
    // ---------------------------------------------------------------------

    @Test
    fun `未落定的行判为可重放，半提交的行判为重放，账本齐了的行判为复用`() {
        assertLocalPort()
        val taskId = fixtureTaskId()

        // --- 未落定、无上游请求 ID ---------------------------------------------------
        val pendingRowId = writer.begin(pendingCall(taskId))
        val pending = journal.recordsOf(taskId).single { it.paramsDigest == digest }
        assertEquals(LocalCommitState.PENDING, pending.localCommitState)
        assertEquals(
            RcaRecoveryAction.REPLAY_READ_ONLY, RcaToolRecovery.decide(pending, state()).action,
            "未落定且无凭据对账的只读调用应当可以重放"
        )

        // --- 已提交，但证据不在账本里（半提交）--------------------------------------
        writer.commit(pendingRowId, committedRecord(), observation(digest))
        val committed = journal.recordsOf(taskId).single { it.paramsDigest == digest }
        assertEquals(
            RcaRecoveryAction.REPLAY_READ_ONLY, RcaToolRecovery.decide(committed, state()).action,
            "证据不在账本时复用会让后续引用指向一条不存在的证据，必须重放补齐"
        )

        // --- 已提交，证据也在账本里 -------------------------------------------------
        val withEvidence = state().copy(evidenceLedger = listOf(ledgerEntry()))
        assertEquals(
            RcaRecoveryAction.REUSE_LOCAL_OBSERVATION, RcaToolRecovery.decide(committed, withEvidence).action,
            "证据已在账本却仍判重放：每次重启都会把已经查过的东西再查一遍"
        )
    }

    @Test
    fun `恢复判定接到真实记录表，不再是那个「这一层没有信息」的实现`() {
        assertLocalPort()
        val taskId = fixtureTaskId()

        assertNotSame(
            RcaRecoveryResolver.NONE, recovery,
            "Worker 仍然装着 NONE：记录表已经有了，再用它等于把「有一条调用无法判定」放行成「没有在途调用」"
        )

        // 只把本行喂进判定：借用任务上可能还有别的行，本用例要断言的是自己这一条。
        val scoped = RcaRecoveryResolver(
            RcaToolCallJournalPort { id -> journal.recordsOf(id).filter { it.paramsDigest == digest } },
            RcaProviderStateUnavailableRepository()
        )
        writer.begin(pendingCall(taskId))
        assertEquals(
            RcaRecoveryOutcome.Proceed, scoped.resolve(taskId, state()),
            "只读、未落定、无凭据对账的调用应当放行到节点里去重放"
        )

        // 落定且证据进账后，判定改为复用而不是重放。
        val committedRowId = journal.recordsOf(taskId).single { it.paramsDigest == digest }.id
        writer.commit(committedRowId, committedRecord(), observation(digest))
        assertEquals(
            RcaRecoveryOutcome.Proceed, scoped.resolve(taskId, state()),
            "半提交的调用同样应当放行（由节点去重放补齐），而不是整体转人工"
        )
    }

    // ---------------------------------------------------------------------
    // 读侧的失败关闭
    // ---------------------------------------------------------------------

    /**
     * `local_commit_state='UNKNOWN'` 是 DDL 允许、而**生产写入侧永远不会写出**的一个状态
     * （`begin` 恒写 PENDING，`commit` 恒写 COMMITTED）。要验的正是读侧遇到它时怎么兜底，
     * 因此这里只能用一次原始 INSERT 把它造出来。该行落在同一个借用任务上并随事务回滚。
     *
     * 这条断言的价值在于方向：认不出来时必须往「无法判定」一侧倒，而不是倒向 PENDING。
     * 倒向 PENDING 会直接走进重放分支，而重放的前提恰恰是「本地账目是可信的」。
     */
    @Test
    fun `库上说未知的落库状态不许被读成可重放`() {
        assertLocalPort()
        val taskId = fixtureTaskId()
        // 与本类的正常用例不同的一组参数，于是摘要也不同：两条记录不会互相干扰。
        val unknownDigest = RcaCanonicalDigest.ofParams(PARAMS + ("state" to "UNKNOWN"))

        jdbc.update(
            """
            INSERT INTO t_rca_tool_call
              (task_id,decision_round,tool_name,tool_version,params_digest,result_status,local_commit_state)
            VALUES (?,?,?,?,?,'UNKNOWN','UNKNOWN')
            """.trimIndent(),
            taskId, ROW_ROUND, RcaToolName.LOG_TRACE.storageCode, TOOL_VERSION, unknownDigest
        )

        val record = journal.recordsOf(taskId).single { it.paramsDigest == unknownDigest }
        assertEquals(LocalCommitState.UNKNOWN, record.localCommitState)
        assertEquals(
            RcaRecoveryAction.REQUIRE_HUMAN, RcaToolRecovery.decide(record, state()).action,
            "库上说 UNKNOWN 的调用被读成了可重放：这把「连请求是否发出都不可知」当成了「没执行过」"
        )
    }

    @Test
    fun `没有本任务之外的记录会被带出来`() {
        assertLocalPort()
        val taskId = fixtureTaskId()
        writer.begin(pendingCall(taskId))

        val other = jdbc.queryForObject(
            "SELECT id FROM t_rca_agent_task WHERE id <> ? ORDER BY id LIMIT 1", Long::class.java, taskId
        )
        if (other == null) return
        assertTrue(
            journal.recordsOf(other).none { it.paramsDigest == digest },
            "按任务取记录时带出了别的任务的行：恢复判定会去看别人的在途调用"
        )
    }

    // ---------------------------------------------------------------------
    // 测试构件
    // ---------------------------------------------------------------------

    private fun pendingCall(taskId: Long): PendingToolCall = PendingToolCall(
        taskId = taskId,
        toolName = RcaToolName.LOG_TRACE.storageCode,
        toolVersion = TOOL_VERSION,
        decisionRound = ROW_ROUND,
        paramsDigest = digest,
        params = PARAMS
    )

    /** 守卫在拿到结果后交给写入侧的那条记录，取自 `ToolInvocationGuard.toRecord` 的实际形状。 */
    private fun committedRecord(): ToolCallRecord = ToolCallRecord(
        toolName = RcaToolName.LOG_TRACE.storageCode,
        toolVersion = TOOL_VERSION,
        paramsDigest = digest,
        decisionRound = ROW_ROUND,
        status = ToolResultStatus.SUCCESS_WITH_DATA,
        localCommitState = LocalCommitState.COMMITTED,
        observationDigest = OBSERVATION_DIGEST,
        observationSummary = SUMMARY,
        evidenceIds = listOf(evidenceId),
        latencyMs = 412,
        inputTokens = 1200,
        outputTokens = 240,
        costUsd = BigDecimal("0.012345")
    )

    private fun observation(digestOfParams: String): ToolResult = ToolResult(
        tool = RcaToolName.LOG_TRACE,
        toolVersion = TOOL_VERSION,
        status = ToolResultStatus.SUCCESS_WITH_DATA,
        paramsDigest = digestOfParams,
        decisionRound = ROW_ROUND,
        observation = ToolObservation(summary = SUMMARY, sourceRef = "logtrace://episode/4242"),
        observationDigest = OBSERVATION_DIGEST
    )

    private fun ledgerEntry(): EvidenceEntry = EvidenceEntry(
        evidenceId = evidenceId,
        type = EvidenceType.LOG_TRACE,
        sourceTool = RcaToolName.LOG_TRACE.storageCode,
        sourceRef = "logtrace://episode/4242",
        coverageType = EvidenceCoverage.EPISODE,
        coverageScope = "logtrace://episode/4242",
        contentDigest = OBSERVATION_DIGEST,
        summary = SUMMARY
    )

    /**
     * 判定需要一个还有预算的状态。
     *
     * `RcaSharedState.seeded` 把预算全给 0，用它会让 `replayDecision` 走「预算已耗尽」
     * 分支而返回转人工——于是本类断言的「可重放」会红在一个与被测行为无关的原因上。
     */
    private fun state(): RcaSharedState = RcaSharedState.seeded(fixtureTaskId(), 1L, NOW).copy(
        gapsAndBudget = GapsAndBudget(
            stepBudget = 12,
            stepsUsed = 0,
            tokenBudget = 60_000,
            tokensUsed = 0,
            costBudgetUsd = BigDecimal("0.5"),
            costUsedUsd = BigDecimal.ZERO,
            deadlineAt = null,
            now = NOW
        )
    )

    private fun toolCallRow(taskId: Long): Map<String, Any?> = jdbc.queryForList(
        "SELECT * FROM t_rca_tool_call WHERE task_id = ? AND params_digest = ?", taskId, digest
    ).single()

    private fun countToolCalls(taskId: Long): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM t_rca_tool_call WHERE task_id = ? AND params_digest = ?",
        Long::class.java, taskId, digest
    )!!

    /**
     * 「只有 span 没有 trace」那条用例的参数摘要。
     *
     * 用本类正常的 [digest] 会让那条用例的行与本类其余用例的行撞在同一个
     * `(任务, 工具, 摘要, 轮次)` 上——[toolCallRow] 按摘要取行就会取到两行而炸。
     * 摘要必须是**真的**规范摘要（满足 `chk_rca_tool_dedup_digest`），只是换一组参数。
     */
    private fun loneSpanDigest(): String = RcaCanonicalDigest.ofParams(PARAMS + ("case" to "lone-span"))

    /** 参数快照里某个键的值，用来证明快照落的是**这次调用真正的参数**。 */
    private fun firstSnapshotValue(row: Map<String, Any?>, key: String): Any? {
        @Suppress("UNCHECKED_CAST")
        val parsed = ObjectMapper().readValue(row["params_snapshot"] as String, Map::class.java)
        return parsed[key]
    }

    private fun fixtureTaskId(): Long = jdbc.queryForObject(
        "SELECT id FROM t_rca_agent_task ORDER BY id LIMIT 1", Long::class.java
    ) ?: error("库里没有任务行，先跑接入测试（RCA_INTAKE_ENTRY）再跑本测试")

    /** 3306 是别的项目的库；本测试会写数据，端口判错就是在写别人的库。 */
    private fun assertLocalPort() {
        assertTrue(
            datasourceUrl.contains(":3307"),
            "工具调用契约测试只允许连本机 feedback-platform 的 3307，当前地址端口不对"
        )
    }

    companion object {
        private const val TOOL_VERSION = "1.0.0"
        private const val ROW_ROUND = 2
        private const val SUMMARY = "首错点：2026-09-28T10:00:00.000 service=pay，错误签名 5xx"

        /**
         * 两份链路标识，与 `chk_rca_agent_trace` / `chk_rca_tool_span` 的格式一致
         * （32 位与 16 位小写十六进制）。
         *
         * 这里用字面量而不是让 `ToolSpanIdFactory` 现推：本类要验的是「两列**真的写得进**
         * 真库的列」，形状与推导规则分别由 `ToolSpanIdFactoryTest` 和 `ToolInvocationGuardTest` 盯。
         * 用推出来的值会让这条用例在推导规则改错时**一起**变红，
         * 而那时失败信息会指向「写不进库」，与真正的原因差得很远。
         */
        private const val TRACE_ID = "0123456789abcdef0123456789abcdef"
        private const val SPAN_ID = "0123456789abcdef"

        /**
         * `observation_digest` 是 `CHAR(64)`，**没有** CHECK 约束兜底：
         * 写短了不会报错，只会在读回来时对不上，于是断言红在一个与被测行为无关的原因上。
         * 所以这里按列宽拼出来，而不是手数一串零。
         */
        private val OBSERVATION_DIGEST: String = "d00d" + "0".repeat(58) + "ff"

        /**
         * 刻意混了字符串与数字两种标量：`RcaCanonicalDigest` 对两者的规范形式不同，
         * 全用字符串的话，「快照能反推摘要」这条断言就验不出类型有没有在往返里变掉。
         */
        private val PARAMS: Map<String, Any?> = linkedMapOf(
            "logId" to "log-1",
            "windowFrom" to "2026-09-28T09:30:00.000",
            "windowTo" to "2026-09-28T10:30:00.000",
            "topK" to 5
        )

        private val NOW: LocalDateTime = LocalDateTime.of(2026, 9, 28, 10, 30)

        /**
         * 只补**测试专用**的固定密钥，不补数据库地址（理由同 `RcaIntakeEndToEndTest`：
         * 一个 3306 的兜底会让本测试静默连上别的项目的库）。
         */
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("app.masking.fingerprint-secret") { "test-fingerprint-secret-at-least-32-characters" }
            registry.add("app.masking.contact-storage-secret") { "test-contact-secret-at-least-32-characters" }
            registry.add("dubbo.enabled") { "false" }
        }
    }
}
