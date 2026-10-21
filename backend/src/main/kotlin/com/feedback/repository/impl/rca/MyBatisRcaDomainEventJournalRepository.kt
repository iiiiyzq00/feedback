package com.feedback.repository.impl.rca

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.agent.rca.domain.RcaDomainEvent
import com.feedback.agent.rca.domain.RcaDomainEventJournal
import com.feedback.agent.rca.tools.RcaEventIdFactory
import com.feedback.mapper.rca.RcaDomainEventMapper
import com.feedback.model.po.rca.RcaDomainEventInsertPo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

/**
 * `t_rca_domain_event` 的写入侧：把 [RcaDomainEvent] 落成一行事件。
 *
 * 名字里的 `Repository` 后缀不是口味问题：本类带 `@Repository` 且落在
 * `com.feedback.repository.**` 下，`RepositoryComplexityGateTest` 要求这类组件的类名
 * 以 `Repository` / `RepositoryImpl` 结尾。它对外暴露的端口仍然是
 * [RcaDomainEventJournal]——后缀服务的是那个门禁，不是领域模型。
 *
 * ## 这个类**不能**加 `@Transactional`（与 `MyBatisRcaToolCallJournalRepository` 的理由相反）
 *
 * 那个类不加事务是为了让两个阶段各自提交；这个类不加事务是为了让写入**加入调用方的事务**。
 * 两者的结论相同、理由不同，改的时候不要照抄对方的原因。
 *
 * 领域事件的全部价值建立在「与状态迁移同生共死」上：迁移动了、事件没写，历史出现无法解释的
 * 断层；迁移回滚了、事件却留着，历史里凭空多出一段没发生过的事。所以它必须和迁移共用
 * 一个事务，也就是**不能有自己的事务边界**。这里加 `@Transactional` 不会报错，
 * 只会让 [record] 在调用方事务内部再获取一次连接（`REQUIRED` 下其实是加入），
 * 真正的危险是有人后来把它改成 `REQUIRES_NEW`——那样断层会立刻出现，而且静默。
 *
 * ## 自己再检查一次「有没有事务」
 *
 * [record] 的第一件事是断言当前线程上有活动事务。这一步是刻意的重复：契约写在端口上、
 * 由调用方遵守，但违反契约的后果不是「写失败」，而是「两条记录各自提交、谁都不报错」——
 * 等到有人拿事件表去对账时，历史已经错了，且无法判断错在哪一次。把它变成一次立刻抛出的失败，
 * 是唯一能让违约在发生的那一刻被发现的做法。
 *
 * 代价要说清：**离线回放与故障注入套件因此不能用这个实现**（它们没有事务，
 * 也不该有——它们断言的是逐字节相同的快照）。那些套件用 [RcaDomainEventJournal.NONE]，
 * 语义是「这个部署没有事件日志」，不是「默认安全」。
 *
 * ## 撞唯一键不是错误
 *
 * `insertIgnore` 影响 0 行表示这次事件之前已经记过（崩溃恢复重入同一节点），
 * 只记一行 INFO。[event_id] 由内容确定性推导，所以「同键」与「同一次事实」等价——
 * 这一点是 [RcaEventIdFactory] 存在的全部意义，本类只是它的使用者。
 *
 * ## 载荷编码
 *
 * 载荷列是 JSON 列，值由本类用 Jackson 拼好再传入（SQL 里不做字符串拼接）。
 * 这里**刻意不共用** `MyBatisRcaToolCallJournalRepository` 里那个形状相近的私有方法：
 * 那一个的约束更强——它写出的文本必须能让人重算出 `params_digest`，
 * 所以它跟着 [com.feedback.agent.rca.tools.RcaCanonicalDigest] 的规范形式走。
 * 本类的载荷只是**发给 MQ 订阅方的线格式**，`event_id` 取的是
 * [com.feedback.agent.rca.tools.RcaCanonicalDigest.ofParams] 的摘要而不是这一列，
 * 因此两者会为了不同的理由各自演化。
 * 抽成一个共用工具会把较严的那个契约强加给本类，换来的只是省下二十行。
 */
@Repository
class MyBatisRcaDomainEventJournalRepository(
    private val mapper: RcaDomainEventMapper
) : RcaDomainEventJournal {

    private val log = LoggerFactory.getLogger(MyBatisRcaDomainEventJournalRepository::class.java)

    /**
     * 与 `MyBatisRcaToolCallJournalRepository` 同一做法：不用容器里的 ObjectMapper 单例。
     * 这里序列化的结果会**离开本进程**（MQ 主题），形状必须由本类自己说了算，
     * 不能随别处对那个共享实例的配置改动而变。
     */
    private val json = ObjectMapper()

    /**
     * 记录一条事件。
     *
     * **不捕获任何异常**：见类文档与端口契约。序列化失败、JSON 列写不进去、
     * 外键（`task_id`）指向不存在的任务——每一种都必须让调用方的事务回滚，
     * 因为每一种都意味着「这次迁移没有可对应的历史」。
     */
    override fun record(event: RcaDomainEvent) {
        requireActiveTransaction(event)

        val inserted = mapper.insertIgnore(
            RcaDomainEventInsertPo(
                eventId = RcaEventIdFactory.of(event),
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                aggregateVersion = event.aggregateVersion,
                eventType = event.type.storageCode,
                taskId = event.taskId,
                // null 原样搬运，不在这里补：两列为空是「这次执行没有会话/链路标识」这个事实，
                // 本层编一个值出来，事后就再也分不出「真没有」与「我们填的」。
                episodeId = event.episodeId,
                traceId = event.traceId,
                payloadJson = json.writeValueAsString(jsonSafe(event.payload))
            )
        )
        if (inserted == 0) {
            log.info(
                "领域事件已存在，本次未新增：task={} version={} type={}。恢复重入同一节点时会走到这里，" +
                    "不是写失败。",
                event.taskId, event.aggregateVersion, event.type.storageCode
            )
        }
    }

    /**
     * 契约的兜底检查，见类文档。检查的是「当前线程上有活动事务」，而不是「本类被谁调用」——
     * 后者查不出来，前者恰好就是决定这两条写入会不会各自提交的那个条件。
     */
    private fun requireActiveTransaction(event: RcaDomainEvent) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "领域事件必须在与状态迁移同一个事务里写入，但当前线程上没有活动事务：" +
                "task=${event.taskId} type=${event.type.storageCode}。没有事务时两条写入会各自提交，" +
                "事件与迁移就不再同生共死——这正是本端口要消除的情形。"
        }
    }

    /**
     * 把载荷收敛成 Jackson 能安全序列化的形状。键排序是为了让同一份载荷在列里逐字节稳定，
     * 便于人工比对（JSON 对象本身无序，所以这不影响订阅方）。
     *
     * 认不出的类型落成类型名而**不抛异常**：载荷里出现一个没预料到的参数类型，
     * 不该让整次状态迁移写不进去——事件缺一条的代价远大于载荷里少一个字段。
     * 但落成类型名也不是无损的，所以它进不了 `event_id` 的推导（那一侧走
     * [com.feedback.agent.rca.tools.RcaCanonicalDigest]，有一模一样的兜底规则，
     * 因此两侧对同一份载荷的判断一致）。
     */
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
        /** 与 `RcaCanonicalDigest` 同一套格式：时间统一到毫秒，避免两侧对同一时刻给出不同文本。 */
        val TIME_FORMATTER: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    }
}
