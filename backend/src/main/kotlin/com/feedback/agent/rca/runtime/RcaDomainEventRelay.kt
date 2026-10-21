package com.feedback.agent.rca.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.feedback.messaging.MessagePublisher
import com.feedback.messaging.MqMessage
import com.feedback.messaging.MqProperties
import com.feedback.messaging.MqPublishException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

/**
 * 把 `t_rca_domain_event` 里未投递的事件发到 MQ 主题。
 *
 * ## 这是「落库 → 投递」的生产入口
 *
 * 在本类之前，事件表只有**写入侧**：`RcaTaskLeaseAdapter` 与
 * `MyBatisRcaPersistenceRepositoryImpl` 在各自的事务里记事件，
 * 而 `published_at` / `publish_attempts` 两列没有任何写入者——它们存在的唯一理由
 * 就是等一个中继，而那个中继此前不存在。本类接的就是这一段。
 *
 * ## 为什么是「读表再发」而不是「写入时顺手发一条」
 *
 * 在写事件的那一刻直接发 MQ，看起来少一次轮询，但会丢掉发件箱模式的全部价值：
 * 那样投递与状态迁移在同一个事务里，**事务回滚时已经发出去的消息收不回来**，
 * 订阅方会收到一条对应的迁移从未发生的事件。本设计里发送由本类独立完成，
 * 事务只负责「事件先落库」——发送失败的代价是这一行停在未投递状态，下次重发。
 *
 * 代价也必须说清：一条事件从落库到出现在主题上，中间隔着一次调度间隔。
 * 因此本链路**不适合当作实时通知**用，它保证的是「最终一定会到、且不会凭空多出来」。
 *
 * ## 失败就停本轮，不是「跳过这条继续发下一条」
 *
 * 事件按自增 id 升序发送，同一聚合的事件因此按发生顺序到达订阅方。
 * 如果第 N 条失败而中继继续发第 N+1 条，那么订阅方会先看到后来的事件、
 * 过一会儿才看到先前那条——顺序错乱从这一刻起是不可修复的（订阅方无法知道要等多久）。
 * 所以 [relayPending] 在第一次失败时就停止本轮：停下的代价是**队首阻塞**
 * （一条永远发不出去的事件会一直被取到、并占掉一个批量名额），
 * 这个代价是刻意的，也正是 `listUnpublished` 的批量上限必须明显大于
 * 「正常情况下一次能发完的量」的原因。
 *
 * ## 与另外两条投递轨的关系
 *
 * 通知发件箱与索引发件箱在 MQ 模式下也走本包（`service/alert`、`service/sync` 下的
 * `*MqTrack`），但它们各自读自己的表、用自己的批量，**不共享本类**：
 * 那两条的投递单位是「一条待投递的投递记录」，本类的投递单位是「一条已落库的事实」，
 * 混在一起会让「谁在重试、重试到第几次」变成一笔糊涂账。
 * 三条轨共用的只有 [MessagePublisher] 与 [MqProperties]。
 */
@Component
class RcaDomainEventRelay(
    private val properties: MqProperties,
    private val publisher: MessagePublisher,
    private val outbox: RcaDomainEventOutbox
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 序列化实现**不共用**容器里的 ObjectMapper 单例，与
     * `MyBatisRcaDomainEventJournalRepository` 同一个理由：这里产出的字节会
     * **离开本进程**（MQ 主题），形状必须由本类自己说了算，
     * 不能随别处对那个共享实例的配置改动而变。
     */
    private val json = ObjectMapper()

    /**
     * 跑一轮投递。
     *
     * 取一批未投递事件（上限 [MqProperties.relayBatchSize]），逐条发送；
     * 成功一条就标记一条，**失败即停止本轮**（理由见类文档）。
     * 一轮只取一批：下一批由下一次调度触发，这样两次调度之间总要经过一个
     * 「别的实例也可能在看同一张表」的窗口，不会有一轮把整表吃完的长事务型循环。
     */
    fun relayPending() {
        if (!properties.enabled) return
        val errors = properties.readinessErrors()
        if (errors.isNotEmpty()) {
            log.error("RCA 领域事件中继配置未就绪，本次调度跳过：errors={}", errors)
            return
        }

        val batch = outbox.listUnpublished(properties.relayBatchSize)
        if (batch.isEmpty()) return

        var published = 0
        var stoppedAt = 0L
        for (event in batch) {
            val message = try {
                buildMessage(event)
            } catch (error: Exception) {
                // 走到这里说明载荷列的内容解析不出来。载荷是 JSON 列、由本平台自己写入，
                // 所以这是「数据被外部改动过」这一类问题，不是常规路径。
                // 与发送失败同样处理（记一次失败并停止本轮），但日志要写明这一条
                // 靠重试不会自愈——继续重试只会一直挡住它后面的所有事件。
                outbox.recordPublishFailure(event.id)
                stoppedAt = event.id
                log.error(
                    "RCA 领域事件载荷无法构造成消息，本轮停止：id={} eventId={} eventType={}。" +
                        "重试不会自愈，需要人工检查这一行的 payload 列。",
                    event.id, event.eventId, event.eventType, error
                )
                break
            }
            try {
                publisher.publish(message)
            } catch (error: MqPublishException) {
                outbox.recordPublishFailure(event.id)
                stoppedAt = event.id
                log.error(
                    "RCA 领域事件投递失败，本轮停止：id={} eventId={} eventType={} key={} 剩余 {} 条未发。" +
                        "这一行会保持未投递状态，下一次调度重试。",
                    event.id, event.eventId, event.eventType, message.key, batch.size - published, error
                )
                break
            }
            if (outbox.markPublished(event.id)) {
                published++
            } else {
                // 影响 0 行：这一行在本次调用之前已被标记成功。并发中继会走到这里，
                // 不是错误，但值得记一行 INFO——它出现得太频繁就说明批量取得过大。
                log.info(
                    "RCA 领域事件已被标记投递（本次未改动）：id={} eventId={}。并发中继会走到这里。",
                    event.id, event.eventId
                )
            }
        }

        log.info(
            "RCA 领域事件投递完成：topic={} 本批={} 已投递={} 停止于id={}",
            properties.rcaDomainEventTopic, batch.size, published, stoppedAt
        )
    }

    /**
     * 事件 → 线格式消息。
     *
     * ## 键取聚合标识，不取事件标识
     *
     * RocketMQ 按消息键的哈希选队列，**同键的消息落在同一个队列上**。
     * 因此键必须是「需要保序的那个单位」——也就是聚合（今天的 `aggregate_id`，等于 task_id），
     * 而不是每条都不同的事件标识。取事件标识会让同一聚合的事件散到不同队列，
     * 保序随之失效，而失效是静默的：订阅方看到的就是乱序。
     *
     * ## 全部字段都写，空值写显式的 null
     *
     * 与 `RcaCanonicalDigest` 同一取向：省略字段与写 null 是两件事。
     * 显式 null 让订阅方能区分「生产方说了这次没有 trace id」与「这个生产方是旧版本、
     * 它不发这个字段」——后者在灰度期间是真实存在的状态。
     *
     * ## 载荷不做二次编码
     *
     * `payload` 列本身是 JSON，这里把它解析成树再嵌进去，得到的是一个 JSON 对象
     * 而不是一个转义过的字符串。若改成 `root.put("payload", payloadJson)`，
     * 订阅方收到的会是一段字符串，必须自己再解一次——那是把生产方的写法泄漏给了每一家订阅方。
     */
    private fun buildMessage(event: PendingRcaDomainEvent): MqMessage {
        val root = json.createObjectNode()
        root.put("eventId", event.eventId)
        root.put("aggregateType", event.aggregateType)
        root.put("aggregateId", event.aggregateId)
        root.put("aggregateVersion", event.aggregateVersion)
        root.put("eventType", event.eventType)
        putNullable(root, "taskId", event.taskId)
        putNullable(root, "episodeId", event.episodeId)
        putNullable(root, "traceId", event.traceId)
        root.put("occurredAt", TIME_FORMATTER.format(event.occurredAt))

        // 载荷列可为 NULL（V244），但由本平台写入的行至少是 `{}`。真遇到 NULL 时给一个空对象
        // **而不是 null**：订阅方对 payload 的处理是一致的（当成对象读字段），
        // 让它还要判一次 null 只会多一个写错的入口。
        val payloadJson = outbox.payloadOf(event.id)
        val payload: JsonNode = if (payloadJson.isNullOrBlank()) {
            json.createObjectNode()
        } else {
            json.readTree(payloadJson)
        }
        root.set<JsonNode>("payload", payload)

        return MqMessage(
            topic = properties.rcaDomainEventTopic,
            key = event.aggregateId.toString(),
            body = json.writeValueAsString(root),
            // 标签取事件类型：本平台不用标签做路由，但它让「只看某类事件」的临时订阅
            // （`mqadmin` 或一个排查用的消费者）不必先解消息体。
            tag = event.eventType,
            headers = mapOf(
                "eventId" to event.eventId,
                "aggregateType" to event.aggregateType
            )
        )
    }

    /**
     * 有值写值，无值写显式 null。两分支都必须存在，见 [buildMessage] 的说明。
     *
     * 两个重载而不是一个收 `Any?` 的：`ObjectNode` 对数字与文本走的是不同的 put，
     * 收 `Any?` 会逼着这里做一次类型判断，而那个判断在编译期什么也证明不了——
     * 一个 `Long` 与一个 `String` 在运行时都只是 `Object`。
     */
    private fun putNullable(root: ObjectNode, name: String, value: Long?) {
        if (value == null) {
            root.putNull(name)
        } else {
            root.put(name, value)
        }
    }

    /** [putNullable] 的文本重载。`traceId` 是字符串，不是数字。 */
    private fun putNullable(root: ObjectNode, name: String, value: String?) {
        if (value == null) {
            root.putNull(name)
        } else {
            root.put(name, value)
        }
    }

    private companion object {
        /** 与 `RcaCanonicalDigest` / 落库精度一致：毫秒。改成别的精度会让两份时间文本对不上。 */
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    }
}
