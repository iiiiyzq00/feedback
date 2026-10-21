package com.feedback.messaging

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently
import org.apache.rocketmq.common.consumer.ConsumeFromWhere
import org.apache.rocketmq.common.message.MessageExt
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component

/**
 * 把容器里所有 [MqMessageHandler] 变成真正的 broker 订阅方。
 *
 * ## 为什么是 push 消费者而不是「XXL 定时拉一批」
 *
 * 「把投递换成真 broker」的实质是**投递由消息驱动，而不是由时钟驱动**：
 * 一条消息到达就处理一条，处理完就确认。用 XXL 去 `receive()` 一批，
 * 只是把轮询从库里搬到 broker 上，横向扩展与背压都还是自己扛。
 * 本平台其余的长任务确实是 XXL 驱动（见 `job/` 下的处理器），
 * 这里是有意的例外，理由就是上面这一句。
 *
 * ## 一次回调一条消息
 *
 * `consumeMessageBatchMaxSize = 1`：并发消费的确认粒度是**整批**，
 * 一批里有一条失败就整批重投，成功的那几条会被再处理一遍。把批大小固定成 1，
 * 「处理失败」的影响面就精确到那一条。代价是吞吐下降——本机验证与当前数据量下
 * 这个代价可以接受，而且订阅方本来就必须幂等（见 [MqMessageHandler]），
 * 所以这个选择是**减少无谓重复**，不是**换取正确性**。
 *
 * ## 重投次数用尽之后
 *
 * broker 会把消息移进 `%DLQ%<组名>`。那里没有消费者，也没有告警——所以每条
 * 处理失败都在日志里留下 WARN，并在最后一次重试时留下 ERROR，写明这个组名。
 * 把死信做成「有人会收到通知」需要一个消费者，本平台没有做，这是已知缺口。
 */
@Component
@ConditionalOnProperty(prefix = "app.mq", name = ["enabled"], havingValue = "true")
class RocketMqMessageConsumerLifecycle(
    private val properties: MqProperties,
    private val handlers: List<MqMessageHandler>
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(RocketMqMessageConsumerLifecycle::class.java)

    private val consumers = mutableListOf<DefaultMQPushConsumer>()

    @Volatile
    private var running = false

    override fun start() {
        handlers.forEach(::startConsumer)
        running = true
        log.info(
            "MQ 消费者已启动：组前缀={} 订阅数={} 主题={}",
            properties.groupPrefix, handlers.size, handlers.map { it.topic }
        )
    }

    private fun startConsumer(handler: MqMessageHandler) {
        val group = properties.consumerGroupOf(handler.topic)
        val consumer = DefaultMQPushConsumer(group).apply {
            namesrvAddr = properties.namesrvAddr
            subscribe(handler.topic, SUBSCRIBE_ALL)
            consumeMessageBatchMaxSize = 1
            consumeThreadMin = MIN_CONSUME_THREADS
            consumeThreadMax = maxOf(properties.consumerThreads, MIN_CONSUME_THREADS)
            maxReconsumeTimes = properties.maxReconsumeTimes
            if (properties.consumeFromFirstOffset) {
                consumeFromWhere = ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET
            }
            registerMessageListener(listenerFor(handler))
        }
        consumer.start()
        consumers += consumer
        log.info(
            "已订阅：topic={} group={} fromFirstOffset={} maxReconsume={}",
            handler.topic, group, properties.consumeFromFirstOffset, properties.maxReconsumeTimes
        )
    }

    private fun listenerFor(handler: MqMessageHandler): MessageListenerConcurrently =
        MessageListenerConcurrently { messages, _ ->
            var anyFailed = false
            messages.forEach { raw ->
                // MessageExt 继承自 Message，所以 tags/keys/userProperty 直接可用。
                val ext = raw as? MessageExt
                if (ext == null) {
                    log.error("收到非 MessageExt 的消息，无法读出重投次数：{}", raw.javaClass.name)
                    anyFailed = true
                    return@forEach
                }
                if (!deliver(handler, ext)) {
                    anyFailed = true
                }
            }
            if (anyFailed) {
                ConsumeConcurrentlyStatus.RECONSUME_LATER
            } else {
                ConsumeConcurrentlyStatus.CONSUME_SUCCESS
            }
        }

    /** @return true 表示已确认。 */
    private fun deliver(handler: MqMessageHandler, ext: MessageExt): Boolean = try {
        handler.handle(
            ConsumedMqMessage(
                topic = ext.topic,
                tag = ext.tags,
                key = ext.keys,
                body = String(ext.body, Charsets.UTF_8),
                msgId = ext.msgId,
                reconsumeTimes = ext.reconsumeTimes,
                // getProperties() 返回的是**全部**属性：既有发送方 putUserProperty 写进去的，
                // 也有客户端自己写的保留键（KEYS / TAGS / WAIT / 轨迹相关）。
                // 这里不再过滤：发送方读 HEADERS 是为了还原自己写下的东西，
                // 而保留键原样带出来，排障时正好能看到 broker 侧认为的 key 与 tag 是什么。
                headers = ext.properties.orEmpty()
            )
        )
        true
    } catch (error: Exception) {
        val group = properties.consumerGroupOf(handler.topic)
        if (ext.reconsumeTimes >= properties.maxReconsumeTimes) {
            log.error(
                "消息处理失败且重投次数已用尽：topic={} group={} msgId={} key={} 重投次数={} → 下一条会进入死信主题 %DLQ%{}，那里没有消费者",
                ext.topic, group, ext.msgId, ext.keys, ext.reconsumeTimes, group, error
            )
        } else {
            log.warn(
                "消息处理失败，等待重投：topic={} group={} msgId={} key={} 重投次数={}/{} 原因={}: {}",
                ext.topic, group, ext.msgId, ext.keys, ext.reconsumeTimes, properties.maxReconsumeTimes,
                error.javaClass.simpleName, error.message.orEmpty()
            )
        }
        false
    }

    override fun stop() {
        running = false
        // 逐个关，不让一个关失败拖住后面的：停机时残留的消费者会让下一次启动拿到
        // 同一个组的旧位点，比多花几毫秒更麻烦。
        consumers.forEach { consumer ->
            runCatching { consumer.shutdown() }
                .onFailure { log.warn("MQ 消费者关闭失败：{}", it.javaClass.simpleName) }
        }
        consumers.clear()
    }

    override fun isRunning(): Boolean = running

    companion object {
        /** 订阅全部标签。本平台不用标签做路由，标签只用于排障。 */
        const val SUBSCRIBE_ALL: String = "*"

        /** 消费线程下限。取 1 是为了让空闲的消费池真的缩到 1，而不是常驻 20 条线程。 */
        const val MIN_CONSUME_THREADS: Int = 1
    }
}
