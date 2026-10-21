package com.feedback.repository.impl.rca

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.agent.rca.domain.LocalCommitState
import com.feedback.agent.rca.domain.ToolCallRecord
import com.feedback.agent.rca.domain.ToolResult
import com.feedback.agent.rca.domain.ToolResultStatus
import com.feedback.agent.rca.runtime.PendingToolCall
import com.feedback.agent.rca.runtime.RcaToolCallJournalPort
import com.feedback.agent.rca.runtime.RcaToolCallJournalWriter
import com.feedback.agent.rca.tools.RcaCanonicalDigest
import com.feedback.mapper.rca.RcaToolCallMapper
import com.feedback.model.po.rca.RcaToolCallPendingPo
import com.feedback.model.po.rca.RcaToolCallPo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * `t_rca_tool_call` 的读写适配器：同时实现恢复判定的读侧与工具边界的写侧。
 *
 * 一个类实现两个端口是刻意的：它们读写同一张表、共享同一套编解码口径
 * （下面是 [toRecord] 与 [commit] 的对称关系）。拆成两个类会让「写进去的形状」
 * 与「读出来的形状」有各自的实现，而这类漂移不会报错，只会让恢复判定悄悄看到
 * 一个与写入时不同的事实。
 *
 * ## 这个类**不能**加 `@Transactional`
 *
 * 两阶段写入的全部意义在于：`begin` 的那条 `PENDING` 行必须在崩溃后**仍然存在**。
 * 如果两个阶段落在同一个事务里，崩溃时整笔回滚，`PENDING` 行随之消失，
 * 崩溃窗口重新变成一片空白——机制看起来接上了，实际上什么都没保住，
 * 而且不会以任何形式报错。所以这两个方法各自自动提交，**不加事务注解是功能的一部分**，
 * 不是疏漏。
 *
 * 这也意味着调用方（[com.feedback.agent.rca.tools.ToolInvocationGuard]）不能把
 * `invoke` 包进一个更大的事务里，否则同样的效果会从外层发生。
 *
 * ## 认不出来的编码一律往保守方向兜底
 *
 * 两处 `fromStorage` 都返回可空，本类显式接了 null 分支而不是用 `?: PENDING` 之类的默认值：
 *
 * - `result_status` 认不出来 → `UNKNOWN`（`evidenceGap = true`）。不许默认为
 *   `SUCCESS_EMPTY` 或 `NOT_FOUND`——那两个都是 `evidenceGap = false` 的**确认性结论**，
 *   兜底到它们等于把一个我们读不懂的记录说成「上游确认没有」。
 * - `local_commit_state` 认不出来 → `LocalCommitState.UNKNOWN`，恢复判定据此转人工。
 *   认成 `PENDING` 会直接走进重放分支，而重放的前提正是「本地账目是可信的」。
 *
 * ## 这一层不抛异常的边界在哪里
 *
 * [recordsOf] 与 [commit] 都不抛业务异常：它们在恢复与取证的关键路径上，
 * 抛出去会中止整次调查。[commit] 影响 0 行只记一条 WARN——那表示这一行已经不再是
 * `PENDING`（多半是重放路径重复回写），不是写失败。
 *
 * [begin] 相反，**让它抛**：数据库写不进去就发不出这次调用，而 Agent 是在
 * 「本地已经记下这次取证」的前提下推进的。让它在没有痕迹的情况下继续，
 * 正是这套设计要消除的那类错误（见 [RcaToolCallJournalWriter] 的两阶段说明）。
 * 抛出的异常会经 `RcaWorker` 的兜底路径把任务交还，而不是被当成工具失败吞掉。
 */
@Repository
class MyBatisRcaToolCallJournalRepository(
    private val mapper: RcaToolCallMapper
) : RcaToolCallJournalPort, RcaToolCallJournalWriter {

    private val log = LoggerFactory.getLogger(MyBatisRcaToolCallJournalRepository::class.java)

    /**
     * 刻意不复用 Spring 容器的 ObjectMapper：这里只用得上「标量集合」一种形状，
     * 而且需要连续几次调用之间行为完全一致（容器里的 ObjectMapper 被别处改过配置时，
     * 落进 JSON 列的形状会跟着变，而这一列的形状是 listByTask 的抽取依据）。
     */
    private val json = ObjectMapper()

    /** 该任务已落库的调用记录，按 `id` 升序。 */
    override fun recordsOf(taskId: Long): List<ToolCallRecord> = mapper.listByTask(taskId).map(::toRecord)

    override fun begin(call: PendingToolCall): Long {
        mapper.findPendingId(
            taskId = call.taskId,
            toolName = call.toolName,
            paramsDigest = call.paramsDigest,
            decisionRound = call.decisionRound
        )?.let { existing ->
            // 崩溃恢复重跑同一个节点时会走到这里。沿用原行而不是新插一条：
            // 同一次取证在库里出现两次，恢复判定会看到两条 PENDING 并各自重放一次。
            log.info(
                "沿用未落定的工具调用行：task={} tool={} round={} row={}",
                call.taskId, call.toolName, call.decisionRound, existing
            )
            return existing
        }
        val row = RcaToolCallPendingPo(
            taskId = call.taskId,
            toolName = call.toolName,
            toolVersion = call.toolVersion,
            decisionRound = call.decisionRound,
            paramsDigest = call.paramsDigest,
            paramsSnapshotJson = snapshotOf(call.params),
            // 原样搬运，不在这里补：两列同为空是「这次执行没有链路标识」这个事实本身，
            // 本层猜一个值出来就再也分不出「真没有」与「我们编的」（见 PendingToolCall 的说明）。
            traceId = call.traceId,
            spanId = call.spanId
        )
        mapper.insertPending(row)
        return row.id
    }

    override fun commit(rowId: Long, record: ToolCallRecord, result: ToolResult) {
        if (rowId == RcaToolCallJournalWriter.NO_ROW) return
        val affected = mapper.commitToolCall(
            id = rowId,
            resultStatus = record.status.storageCode,
            providerRequestId = record.providerRequestId,
            observationDigest = record.observationDigest,
            // 只放摘要正文，且没有观察结果时整列为 NULL —— 见 RcaToolCallMapper.xml 的说明。
            observationSummaryJson = result.observation?.summary?.let {
                json.writeValueAsString(mapOf(SUMMARY_KEY to it))
            },
            evidenceId = record.evidenceIds.firstOrNull(),
            latencyMs = record.latencyMs,
            inputTokens = record.inputTokens,
            outputTokens = record.outputTokens,
            costUsd = record.costUsd
        )
        if (affected == 0) {
            log.warn(
                "工具调用回写未推进任何行：row={} tool={} status={}。该行多半已不是 PENDING" +
                    "（重放路径重复回写），不作为写失败处理。",
                rowId, record.toolName, record.status.storageCode
            )
        }
    }

    private fun toRecord(row: RcaToolCallPo): ToolCallRecord = ToolCallRecord(
        id = row.id,
        toolName = row.toolName,
        toolVersion = row.toolVersion,
        paramsDigest = row.paramsDigest,
        decisionRound = row.decisionRound,
        status = ToolResultStatus.fromStorage(row.resultStatus) ?: ToolResultStatus.UNKNOWN,
        providerRequestId = row.providerRequestId,
        localCommitState = LocalCommitState.fromStorage(row.localCommitState) ?: LocalCommitState.UNKNOWN,
        // 空串归一成 null：列上允许写入空串，但「摘要为空」在恢复判定里是一个有意义的状态
        // （已落定的失败/缺口），让两者长得不同会让同一种情况分成两条分支。
        observationDigest = row.observationDigest?.takeIf(String::isNotEmpty),
        observationSummary = row.observationSummary?.takeIf(String::isNotEmpty),
        evidenceIds = listOfNotNull(row.evidenceId?.takeIf(String::isNotEmpty)),
        replayedFromId = row.replayedFromId,
        latencyMs = row.latencyMs,
        inputTokens = row.inputTokens,
        outputTokens = row.outputTokens,
        costUsd = row.costUsd
    )

    /**
     * 参数快照。形状**刻意贴近** [RcaCanonicalDigest] 的规范形式：键按字典序、
     * 时间按同一套毫秒精度格式、认不出的复杂类型落成类型名。
     * 这样拿到一行记录的人可以只凭快照重算出 `params_digest`，从而核对
     * 「这条记录确实是那次取证的记录」，而不是只能相信它。
     *
     * 唯一与规范形式不同的是**转义交给 Jackson**：`RcaCanonicalDigest` 是纯 JDK 实现
     * （它所在的 `agent/rca/tools` 刻意不依赖任何框架），它对字符串的加引号不处理转义——
     * 那对摘要没有影响（摘要只要求确定性），但会让含引号的参数写出非法 JSON，
     * 而这一列是 JSON 列，写非法值会整条语句失败。
     *
     * 认不出的类型不抛异常而是落成类型名，与规范形式一致：快照是取证材料，
     * 不能因为某个参数形状没预料到就让整次工具调用发不出去。
     */
    private fun snapshotOf(params: Map<String, Any?>): String = json.writeValueAsString(jsonSafe(params))

    private fun jsonSafe(value: Any?): Any? = when (value) {
        null, is String, is Number, is Boolean -> value
        is LocalDateTime -> TIME_FORMATTER.format(value)
        is Map<*, *> -> value.entries
            .map { (key, entryValue) -> key.toString() to jsonSafe(entryValue) }
            .sortedBy { (key, _) -> key }
            .associate { it }
        is Iterable<*> -> value.map(::jsonSafe)
        is Array<*> -> value.map(::jsonSafe)
        else -> "<unsupported:${value.javaClass.name}>"
    }

    private companion object {

        /** `observation_snapshot` 的形状只有一个键，抽取侧（XML）按同一个键取值。 */
        const val SUMMARY_KEY = "summary"

        /** 与 `RcaCanonicalDigest` 同一套格式：时间统一到毫秒，避免微秒差异造成摘要漂移。 */
        val TIME_FORMATTER: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    }
}
