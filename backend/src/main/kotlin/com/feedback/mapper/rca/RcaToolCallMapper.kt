package com.feedback.mapper.rca

import com.feedback.model.po.rca.RcaToolCallPendingPo
import com.feedback.model.po.rca.RcaToolCallPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal

/**
 * `t_rca_tool_call` 的读写语句：发起、回写、按任务取回。
 *
 * 参数一律用具名 `@Param`，单 PO 入参除外——那种情况下 XML 里必须用**裸属性名**
 * （`#{taskId}` 而不是 `#{row.taskId}`），这是本仓库既有的写法。
 * [insertPending] 是单 PO 入参，[commitToolCall] 是具名参数，两者不要混。
 *
 * ## 这张表为什么没有唯一键，以及幂等怎么保证
 *
 * 表上的 `idx_rca_tool_dedup (task_id, tool_name, params_digest, decision_round)`
 * 是**普通索引不是唯一索引**（V242 的 DDL 如此），所以 `ON DUPLICATE KEY UPDATE`
 * 在这里挡不住重放——[com.feedback.agent.rca.tools.ToolInvocationGuard] 的调用可能被
 * 崩溃恢复重跑。幂等因此走本仓库的第三种手段：**先按业务键查回已有主键**
 * （[findPendingId]），命中就直接沿用那一行。
 *
 * 为什么不干脆加一个唯一索引：那需要一条新的 Flyway 迁移去改 V242 建好的表，
 * 而本次改动的前提是「重建表结构」与「补写入侧」是两件事。用一个走索引的
 * 先查后写换掉一次 schema 变更，代价是一次多出来的单行查询（`idx_rca_tool_dedup`
 * 的前三列就是查询条件，是一次索引点查）。
 *
 * 另有一处**必须注意**：`commitToolCall` 只更新仍是 `PENDING` 的行。
 * `useAffectedRows` 若被打开，「值没变所以影响 0 行」与「状态已不是 PENDING」
 * 会得到同一个返回值。调用方据此判断时不要把它当成错误——本方法返回 0 只表示
 * 「这次没有推进任何行」，不代表写失败。
 */
@Mapper
interface RcaToolCallMapper {

    /**
     * 找出同一 `(任务, 工具, 参数摘要, 轮次)` 下**尚未落定**的调用行主键。
     *
     * 只找 `PENDING` 是有意的：已提交的行不能沿用，否则重放会覆盖一条
     * 已经产生过证据的结论；而沿用未落定的行正是「把崩溃后的重跑对准同一次取证」。
     */
    fun findPendingId(
        @Param("taskId") taskId: Long,
        @Param("toolName") toolName: String,
        @Param("paramsDigest") paramsDigest: String,
        @Param("decisionRound") decisionRound: Int
    ): Long?

    /**
     * 落一条发起行；主键由入参 PO 的 `id` 回填。
     *
     * 语句固定写 `local_commit_state='PENDING'` 与 `result_status='UNKNOWN'`，
     * 理由见 [RcaToolCallPendingPo]：此刻确实还没有结果，
     * 而 `chk_rca_tool_result_status` 的取值集合里也没有「进行中」这个值。
     */
    fun insertPending(row: RcaToolCallPendingPo): Int

    /**
     * 回写调用结果，把记录推到 `COMMITTED`。
     *
     * `local_commit_state` 由语句固定为 `COMMITTED`，不开放给调用方：这条语句的
     * 全部意义就是「本地已经落定」，允许调用方传别的值只会让两阶段写入失效。
     */
    fun commitToolCall(
        @Param("id") id: Long,
        @Param("resultStatus") resultStatus: String,
        @Param("providerRequestId") providerRequestId: String?,
        @Param("observationDigest") observationDigest: String?,
        @Param("observationSummaryJson") observationSummaryJson: String?,
        @Param("evidenceId") evidenceId: String?,
        @Param("latencyMs") latencyMs: Int?,
        @Param("inputTokens") inputTokens: Int?,
        @Param("outputTokens") outputTokens: Int?,
        @Param("costUsd") costUsd: BigDecimal?
    ): Int

    /** 该任务已落库的调用记录，按 `id` 升序。恢复判定按这个顺序逐条处理。 */
    fun listByTask(@Param("taskId") taskId: Long): List<RcaToolCallPo>
}
