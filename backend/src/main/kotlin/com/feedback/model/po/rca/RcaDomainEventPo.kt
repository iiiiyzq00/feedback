package com.feedback.model.po.rca

import java.time.LocalDateTime

/**
 * 领域事件读出行，对应 `t_rca_domain_event`。
 *
 * 读侧**不取** `payload`、也不取 `publish_attempts`：
 * `listByTask` 的用途是排障取证（「这个任务经历过哪些迁移」），载荷是细节，
 * 需要时按 `event_id` 单独查。把它带进每一次列表读，等于让一个手工查询
 * 顺带把 MQ 载荷全文拉进内存。
 *
 * `publishedAt` 要取——它是**唯一**能回答「这条事件有没有发出去」的字段，
 * 而这个问题在 MQ 开启时是排查投递停止的第一个问题。
 */
data class RcaDomainEventPo(
    val id: Long,
    val eventId: String,
    val aggregateType: String,
    val aggregateId: Long,
    val aggregateVersion: Long,
    val eventType: String,
    val taskId: Long?,
    val episodeId: Long?,
    val traceId: String?,
    val occurredAt: LocalDateTime,
    val publishedAt: LocalDateTime?
)
