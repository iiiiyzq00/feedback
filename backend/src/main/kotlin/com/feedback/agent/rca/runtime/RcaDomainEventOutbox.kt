package com.feedback.agent.rca.runtime

import java.time.LocalDateTime

/**
 * 中继读到的一条待投递事件。
 *
 * 与 [com.feedback.model.po.rca.RcaDomainEventPo] 的区别只有一处，但那一处是本质的：
 * 本类**不带载荷**。载荷是 JSON 列，长度不可控，而一份 [PendingRcaDomainEvent] 列表
 * 的用途是「决定接下来发哪几条」——把全文带进来会让每次轮询都为一批可能永远不被
 * 消费的 JSON 付内存代价。真正要发的那一刻才按 [id] 单独取（见 [RcaDomainEventOutbox.payloadOf]）。
 *
 * [eventId] 是确定性事件标识（`sha256(聚合|版本|类型|载荷摘要)` 前 32 位），
 * 它同时是订阅方的幂等键与「这条事件是不是重放出来的」的判据。
 * [id] 是自增行号，**只用做取载荷与标记投递的定位**，不对外承诺任何语义。
 */
data class PendingRcaDomainEvent(
    val id: Long,
    val eventId: String,
    val aggregateType: String,
    val aggregateId: Long,
    val aggregateVersion: Long,
    val eventType: String,
    val taskId: Long?,
    val episodeId: Long?,
    val traceId: String?,
    val occurredAt: LocalDateTime
)

/**
 * 领域事件日志的**投递侧**端口。
 *
 * ## 与 [com.feedback.agent.rca.domain.RcaDomainEventJournal] 的分工
 *
 * 那个端口是**写入侧**：它只能记事件，而且必须与状态迁移同事务。本端口是**读出与投递侧**：
 * 它只能读未投递的事件、并把它们标记成已投递，**没有任何写事件的方法**。
 *
 * 分开的理由不是对称好看，而是两个方向的正确性条件完全不同：写入侧的正确条件是
 * 「和迁移同生共死」，需要事务；投递侧的正确条件是「发出去的才标记、没发出去的下次还取得到」，
 * 需要的是**两侧各自提交**——如果标记投递和发送在同一个事务里，那么每次发送都必须等到
 * 事务提交，而事务提交失败会把「已经发出去的消息」退回成未投递状态，于是同一条事件被
 * 重复投递且无法察觉。所以本端口的实现**不带事务**，每一次标记各自提交。
 *
 * ## 为什么没有「放弃」这一类方法
 *
 * 与 `RcaDomainEventMapper.recordPublishFailure` 同一条纪律：没有「试满 N 次就放弃」。
 * 放弃一条事件不会有任何人收到通知，而它会安静地留在库里永远是未投递状态；
 * 相形之下，反复重试一个坏主题至少会在监控上留下持续增长的计数。
 * 因此本端口只有「标记成功」与「记一次失败」两个出口。
 */
interface RcaDomainEventOutbox {

    /**
     * 取一批尚未投递的事件，**按写入顺序（自增 id 升序）**。
     *
     * 顺序是契约的一部分：同一聚合的事件必须按发生顺序到达订阅方，
     * 而中继按这个顺序逐条发送是唯一能保证这一点的做法（丢弃失败后的续发会破坏它，
     * 见 [RcaDomainEventRelay] 的说明）。
     */
    fun listUnpublished(limit: Int): List<PendingRcaDomainEvent>

    /**
     * 按行号取载荷原文（JSON 文本），没有载荷时为 null。
     *
     * 返回**文本**而不是已解析的结构：本层不替 MQ 订阅方决定用什么反序列化器，
     * 也不把一份解析结果缓存在这里。中继把它作为 JSON 值嵌进消息体，
     * 不做字符串转义——这是「载荷不二次编码」这条约定对实现的要求。
     */
    fun payloadOf(id: Long): String?

    /**
     * 标记已投递。**只有确认消息到达 broker 之后才允许调用**。
     *
     * @return true 表示本次调用把状态从「未投递」改成了「已投递」；
     * false 表示这一行在调用时已经是已投递状态（并发中继或重入）。两者都不算错误。
     */
    fun markPublished(id: Long): Boolean

    /**
     * 记一次投递失败，**不改动已投递状态**。
     *
     * @return true 表示计数已递增；false 表示这一行在这一刻已经投递成功。
     */
    fun recordPublishFailure(id: Long): Boolean
}
