package com.feedback.model.po.rca

import java.math.BigDecimal

/**
 * `t_rca_agent_task` 的行视图。
 *
 * 只包含租约与恢复相关的列，不含 `state_snapshot` 之外的业务明细：
 * 任务行的职责是「谁在跑、跑到哪、还能跑几次」，证据与假设在各自的表里。
 *
 * 字段顺序与 [com.feedback.agent.rca.runtime.RcaClaimedTask] 无关，
 * 由 MyBatis 按列名注入（`map-underscore-to-camel-case` 已开启），
 * 因此这里的字段增删不会影响任何位置参数构造。
 */
data class RcaAgentTaskPo(
    var id: Long = 0,
    var taskKey: String? = null,
    var episodeId: Long = 0,
    var taskStatus: String? = null,
    var currentNode: String? = null,
    var stateVersion: Long = 0,
    var leaseOwner: String? = null,
    var leaseGeneration: Long = 0,
    var stateSnapshot: String? = null,
    var snapshotSha256: String? = null,
    var attemptCount: Int = 0,
    var maxAttempts: Int = 0,
    /**
     * 链路追踪标识，32 位小写十六进制，由 `claim` 语句在领取时补上且只补一次。
     *
     * 可空：这一列是后加的，早于它存在的行（743 条夹具任务及其调用记录）如实为 null，
     * 而不是被回填一个编出来的值。
     */
    var traceId: String? = null,
    var stepsUsed: Int = 0,
    var tokensUsed: Int = 0,
    var costUsedUsd: BigDecimal = BigDecimal.ZERO
) {
    /** 租约剩余时间（毫秒），由 SQL 用数据库时间算出，不用 Worker 时钟。 */
    var leaseRemainingMs: Long = 0

    /**
     * 截止时间剩余毫秒数，同样由 SQL 用数据库时间算出。
     *
     * **为 null 与为负数是两件事**：null 表示这一列还没被写过（`claim` 之前的行），
     * 负数表示已经超期。可空而不是兜底成 0，是因为 0 恰好落在「刚好到期」这个边界上，
     * 把「没有截止时间」读成「刚刚到期」会让一个从未设过截止时间的任务被立刻收口。
     * 与 `lease_remaining_ms` 不同，这一列**不夹到 0**：符号本身是 Worker 的判据。
     */
    var deadlineRemainingMs: Long? = null
}
