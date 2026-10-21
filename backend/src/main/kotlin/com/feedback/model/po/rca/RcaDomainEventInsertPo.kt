package com.feedback.model.po.rca

/**
 * 领域事件写入行，对应 `t_rca_domain_event`。
 *
 * [eventId] 由 `RcaEventIdFactory` 从**事件内容**确定性推导，不是数据库生成的：
 * 唯一键 `uk_rca_domain_event_id` 上撞键的含义是「这件事件已经记过了」，
 * 插入语句据此用 `INSERT IGNORE` 静默跳过——重放因此不会在历史里留下第二条。
 *
 * `occurredAt` 刻意**不在本 PO 上**：时间由 SQL 的 `NOW(3)` 决定（与
 * `RcaTaskLeaseMapper.xml` 同一条纪律），不经过 Kotlin 侧。曾经的设计是让调用方传一个
 * 「与状态迁移同一个 `NOW(3)`」，但那要求调用方先读一次数据库时钟，那个读数本身又是
 * 第三条语句——不仅没有更准，反而多引入一个可以出错的环节。
 *
 * `payloadJson` 是**调用方拼好的** JSON 文本（与 `RcaToolCallPendingPo.paramsSnapshotJson`
 * 同一约定）：SQL 里不做字符串拼接，本文件的使用方也不得让 SQL 决定载荷形状。
 *
 * `publishedAt` / `publishAttempts` 同样刻意**不出现在本 PO 上**：它们属于 MQ 传输，
 * 由中继独占写入，领域侧的插入语句不碰这两列。
 */
data class RcaDomainEventInsertPo(
    val eventId: String,
    val aggregateType: String,
    val aggregateId: Long,
    val aggregateVersion: Long,
    val eventType: String,
    val taskId: Long,
    val episodeId: Long?,
    val traceId: String?,
    val payloadJson: String?
)
