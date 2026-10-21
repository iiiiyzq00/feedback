package com.feedback.repository.impl.rca

import com.feedback.agent.rca.runtime.PendingRcaDomainEvent
import com.feedback.agent.rca.runtime.RcaDomainEventOutbox
import com.feedback.mapper.rca.RcaDomainEventMapper
import com.feedback.model.po.rca.RcaDomainEventPo
import org.springframework.stereotype.Repository

/**
 * [RcaDomainEventOutbox] 的 MyBatis 实现。
 *
 * 名字里的 `Repository` 后缀与 [MyBatisRcaDomainEventJournalRepository] 同一个理由：
 * `RepositoryComplexityGateTest` 要求 `com.feedback.repository.**` 下带 `@Repository`
 * 的组件类名以 `Repository` / `RepositoryImpl` 结尾。对外暴露的端口是
 * [RcaDomainEventOutbox]——后缀服务的是那个门禁。
 *
 * ## 这个类**不能**加 `@Transactional`
 *
 * 与 [MyBatisRcaDomainEventJournalRepository] 的理由**正好相反**，不要照抄那边的说明：
 * 那边的结论是「不能有自己的事务边界」，为的是让写入加入调用方的事务；
 * 这边的结论也是「不能有自己的事务边界」，但为的是让每一次标记**各自提交**。
 *
 * 投递侧的正确性是「发出去的才标记、没发出去的下次还取得到」。把一次批量投递
 * 包进一个事务，会让「已经发出去的消息」在事务回滚时退回未投递状态——
 * 于是同一条事件被重复投递，而重复本身没有任何地方会报错。
 * 反过来说，标记一次失败也不该回滚已经成功的那些标记：同一批里第 3 条失败
 * 不该把第 1、2 条的「已投递」抹掉，那会让它们被再发一遍。
 *
 * ## 影响行数为 0 是正常结果，不是错误
 *
 * [markPublished] 与 `recordPublishFailure` 的 SQL 条件里都有 `published_at IS NULL`，
 * 所以影响 0 行只有两种可能：这一行在本次调用之前已经被标记成功（并发中继或重入），
 * 或者它已经被别的实例发出去过。两种都不该抛异常——把 0 行翻译成异常会逼着
 * 调用方写一个「忽略这个异常」的 catch，而那正是最容易写错的地方。
 * 因此这里把影响行数翻译成布尔值返回，让调用方**看见**这个事实并自己决定要不要记录。
 */
@Repository
class MyBatisRcaDomainEventOutboxRepository(
    private val mapper: RcaDomainEventMapper
) : RcaDomainEventOutbox {

    /** 逐行搬运。转换逻辑只有一处，不在 SQL 里补任何默认值——见 [toPending] 的说明。 */
    override fun listUnpublished(limit: Int): List<PendingRcaDomainEvent> =
        mapper.listUnpublished(limit).map(::toPending)

    /** 载荷为 JSON 列，读出的是列的字符形式；为 NULL 时返回 null，由调用方自行兜底。 */
    override fun payloadOf(id: Long): String? = mapper.selectPayloadById(id)

    override fun markPublished(id: Long): Boolean = mapper.markPublished(id) == 1

    override fun recordPublishFailure(id: Long): Boolean = mapper.recordPublishFailure(id) == 1

    /**
     * 行 → 待投递事件。
     *
     * 两个可空字段（`episodeId` / `traceId`）**原样搬运，不兜底**：
     * 它们为 NULL 表示「这次执行没有会话 / 链路标识」，这是一个事实。
     * 在这一层编一个值出来，事后就再也分不出「真没有」与「我们填的」——
     * 而领域事件的全部用途恰恰是事后取证。
     *
     * `taskId` 在表上是 `BIGINT DEFAULT NULL`（V244），读出来因此可空；
     * 这里不做非空断言：一条没有任务归属的事件在数据上是允许的
     * （聚合标识与任务标识是两个字段，见 V244 的列注释），
     * 用断言把它变成崩溃只会让中继停在一个本来能发出去的载荷上。
     */
    private fun toPending(row: RcaDomainEventPo): PendingRcaDomainEvent = PendingRcaDomainEvent(
        id = row.id,
        eventId = row.eventId,
        aggregateType = row.aggregateType,
        aggregateId = row.aggregateId,
        aggregateVersion = row.aggregateVersion,
        eventType = row.eventType,
        taskId = row.taskId,
        episodeId = row.episodeId,
        traceId = row.traceId,
        occurredAt = row.occurredAt
    )
}
