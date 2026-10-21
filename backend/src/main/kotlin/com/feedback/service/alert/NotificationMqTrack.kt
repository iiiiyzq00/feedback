package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.messaging.ConsumedMqMessage
import com.feedback.messaging.MessagePublisher
import com.feedback.messaging.MqMessage
import com.feedback.messaging.MqMessageHandler
import com.feedback.messaging.MqProperties
import com.feedback.messaging.MqPublishException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 通知投递的 MQ 轨：中继发号，消费者按号投递。
 *
 * ## 为什么消息体里只有行号
 *
 * 通知正文（`notify_content`）与收件人解析结果都是**告警内容**：标题、摘要、指标值，
 * 以及指向某个反馈实例的标识。把正文放进消息体，等于把这些内容复制到一个
 * 本平台的信任边界之外的地方（broker 的存储、broker 的日志、任何订阅了该主题的进程），
 * 而投递本来只需要「投哪一条」。所以线格式只有 `{"id": <通知行号>}`，
 * 消费者凭行号回到库里取正文——**正文一次都没有离开数据库**。
 *
 * 代价要说清：消费者必须能读库，因此本轨**不能**用来把投递卸载到一台无库访问的机器上。
 * 换来的是「消息在 broker 上停留多久，敏感内容就暴露多久」这个问题彻底不存在。
 *
 * ## 中继的失败处置：不新建机制
 *
 * 中继认领一条记录就把它标成 SENT（这是 `claim` 的一部分，不是中继自己写的），
 * 所以「认领成功、发布失败」会留下一行 SENT 而消息没到主题。
 * 这里**不引入任何补偿机制**：`claim` 的 SQL 里本来就有
 * `notify_status='SENT' AND updated_at < DATE_SUB(NOW(3), INTERVAL 60 SECOND)` 这一条，
 * 它原本用于修复「投递进程崩溃」，恰好也是「中继没把号发出去」的修复路径——
 * 60 秒后这条记录会被重新认领并重发。用已有的修复路径而不是再加一条，
 * 是因为两条修复路径的触发条件一旦不完全一致，就会出现「谁都不认领」的死角。
 *
 * 发布失败时**停止本轮**：继续认领会把更多记录标成 SENT 却一条也发不出去，
 * 下一轮 60 秒后它们会一起回来，把失败放大成一批。停在一处，日志里的行号就是唯一的待办。
 *
 * ## 重复是常态，不是异常
 *
 * 同一条通知可能被投递两次：中继的 60 秒重认领、broker 的重投、以及
 * 「消费成功但确认丢失」都会造成重复。第二次走到 [handle] 时行已经不是 SENT 状态，
 * `deliverClaimed` 返回 false，这里的处置是**确认掉**（正常返回），
 * 而不是抛异常换一次注定同样结果的重投。真正的重复投递（两次都到了外部渠道）
 * 由渠道侧的幂等键兜底——投递时带的是 `event.eventId`（见 `NotificationDispatcher.postJson`）。
 */
@Component
class NotificationMqTrack(
    private val properties: MqProperties,
    private val publisher: MessagePublisher,
    private val outbox: NotificationOutbox,
    private val dispatcher: NotificationDispatcher
) : MqMessageHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 线格式由本类自己拼：不用容器里的 ObjectMapper 单例，与
     * `RcaDomainEventRelay` 同一个理由——产出或解读的字节会离开本进程，
     * 形状不该随别处对共享实例的配置改动而变。
     */
    private val json = ObjectMapper()

    override val topic: String get() = properties.notificationTopic

    /**
     * 跑一轮中继：认领一条就发一条的号，直到没有待投递记录或达到批量上限。
     *
     * 逐条认领而不是批量认领，是因为 `claim` 一次只取一行（`LIMIT 1`）且带
     * `FOR UPDATE SKIP LOCKED`：批量版本要让多实例分摊，需要把选择与加锁分开，
     * 那正是「两个实例认领到同一行」的经典入口。这里的代价是每行一次事务，
     * 对本平台的告警量来说可以接受。
     */
    fun relayPending() {
        if (!properties.enabled) return
        val errors = properties.readinessErrors()
        if (errors.isNotEmpty()) {
            log.error("通知 MQ 中继配置未就绪，本次调度跳过：errors={}", errors)
            return
        }

        var relayed = 0
        var stopped = false
        while (relayed < properties.relayBatchSize) {
            val row = outbox.claimOne() ?: break
            try {
                publisher.publish(
                    MqMessage(
                        topic = properties.notificationTopic,
                        // 键取行号：同一行只可能有一个投递动作，取行号让「这条通知的
                        // 投递消息」始终落在同一个队列上；顺序对通知本身不重要，
                        // 但同键同队列让 broker 上的排查（`queryMsgByKey`）能直接按行号捞。
                        key = row.id.toString(),
                        body = json.writeValueAsString(mapOf("id" to row.id)),
                        tag = NORMALIZED_TAG,
                        headers = mapOf("outboxId" to row.id.toString())
                    )
                )
                relayed++
            } catch (error: MqPublishException) {
                stopped = true
                log.error(
                    "通知中继投递失败，本轮停止：outboxId={} channel={} 本轮已发出 {} 条。" +
                        "这一行已处于 SENT 状态且没有消息在主题上，60 秒后由发件箱的陈旧 SENT 规则重新认领。",
                    row.id, row.channel, relayed, error
                )
                break
            }
        }

        if (relayed > 0 || stopped) {
            log.info(
                "通知中继完成：topic={} 已发号={} 因失败停止={}",
                properties.notificationTopic, relayed, stopped
            )
        }
    }

    /**
     * 消费一条投递消息。
     *
     * 体解析不出来就抛——它会重投 [MqProperties.maxReconsumeTimes] 次，然后进
     * `%DLQ%<组名>`。这是刻意的：一条体不合法的消息**不会**因为多试几次而变合法，
     * 但静默确认掉会让它彻底消失，而进死信至少留下一个可以查的位置。
     * 抛之前不需要自己记日志：`RocketMqMessageConsumerLifecycle` 会在最后一次重试时
     * 记一条带组名的 ERROR。
     */
    override fun handle(message: ConsumedMqMessage) {
        val id = parseOutboxId(message)
        if (dispatcher.deliverClaimed(id)) {
            log.debug("通知已投递：outboxId={} msgId={}", id, message.msgId)
        } else {
            log.info(
                "通知不在待投递状态，确认掉这条消息：outboxId={} msgId={} 重投次数={}。" +
                    "重复投递（中继重认领、broker 重投）会走到这里，不是错误。",
                id, message.msgId, message.reconsumeTimes
            )
        }
    }

    /**
     * 从消息体里取出行号。
     *
     * 校验的是「能拿到一个正数」而不是「体是不是合法的 JSON」：前者是投递真正需要的条件，
     * 后者只是它的一种实现方式。行号缺失、为 0、为负，都不指向任何一条记录——
     * 与其让 `deliverClaimed` 去查一次注定查不到的行然后把它当成「已处理」确认掉，
     * 不如在这里就失败，让消息进死信。
     */
    private fun parseOutboxId(message: ConsumedMqMessage): Long {
        val node = runCatching { json.readTree(message.body) }.getOrNull()
            ?: throw IllegalArgumentException(
                "通知投递消息体不是合法 JSON：topic=${message.topic} msgId=${message.msgId}"
            )
        val id = node["id"]?.takeIf { it.isNumber }?.asLong()
            ?: throw IllegalArgumentException(
                "通知投递消息体缺少数字字段 id：topic=${message.topic} msgId=${message.msgId}"
            )
        if (id <= 0) {
            throw IllegalArgumentException(
                "通知投递消息体的 id 必须为正数，当前为 $id：topic=${message.topic} msgId=${message.msgId}"
            )
        }
        return id
    }

    private companion object {
        /**
         * 标签是常量而不是渠道名。
         *
         * 用渠道名做标签看起来更有用，但那会把「渠道」这个取值复制到 broker 上，
         * 而渠道取值直接对应收件人配置（`app.alert.*`）——这属于本平台内部的
         * 部署细节，没有理由让它离开进程。要按渠道过滤时，正文里有渠道信息，
         * 或者直接查库更准。
         */
        const val NORMALIZED_TAG: String = "NOTIFICATION"
    }
}
