package com.feedback.messaging

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently
import org.apache.rocketmq.common.consumer.ConsumeFromWhere
import org.apache.rocketmq.common.message.MessageExt
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 订阅方探针：本平台自己的消费者**之外的**一个独立订阅方。
 *
 * 与 `MqMessageHandler` 那种「在应用进程里处理消息」的角色不同，探针不处理任何业务，
 * 它只把收到的原始消息留下来供断言读。两条用途：
 *
 * 1. **证明消息真的在 broker 上**——而不是「`send` 没抛异常」。这两件事不一样：
 *    发送成功只说明主节点接下了这条消息，探针收到才说明它可被订阅方读到。
 * 2. **拿到消息的原始字节**（含 user property、tags、keys）。线格式的契约是字节级的，
 *    从应用自己的处理器里反推读不出「header 到底带没带」。
 *
 * ## 每次都要用一个**没用过**的消费者组
 *
 * RocketMQ 的组消费位点是服务端存的，同一组第二次启动会从上次的位点继续。
 * 探针要的是「从我开始订阅之后发出的消息」，所以调用方应当给一个随机组名
 * （见 [MqProbe.unique]）——复用组名会让第二次运行读到第一次的残留位点，
 * 用例于是变成「有时通过」，而那种红是最难查的。
 */
class MqProbe(
    namesrvAddr: String,
    group: String,
    topic: String,
    fromFirstOffset: Boolean = false
) : AutoCloseable {

    private val received = LinkedBlockingQueue<MessageExt>()

    private val consumer = DefaultMQPushConsumer(group).apply {
        this.namesrvAddr = namesrvAddr
        subscribe(topic, "*")
        // 与 `RocketMqMessageConsumerLifecycle` 同样的理由：确认粒度是整批，
        // 探针一次只收一条，避免「批里一条放弃导致整批重投」把断言用的队列塞满重复。
        consumeMessageBatchMaxSize = 1
        consumeThreadMin = 1
        consumeThreadMax = 2
        maxReconsumeTimes = 0
        if (fromFirstOffset) {
            consumeFromWhere = ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET
        }
        registerMessageListener(
            MessageListenerConcurrently { messages, _ ->
                messages.forEach { received += it }
                ConsumeConcurrentlyStatus.CONSUME_SUCCESS
            }
        )
    }

    fun start() = consumer.start()

    /**
     * 等一条消息，直到 [predicate] 说「就是这条」或者超时。
     *
     * **不是「等第一条」**：主题上会有别的用例、别的运行留下来的消息，
     * 断言必须按内容挑，否则一次并行运行就会互相污染。挑不中的消息直接丢掉，
     * 因为它们对本用例没有意义，留着只会把「没等到」的时间拉长。
     */
    fun await(predicate: (MessageExt) -> Boolean, timeoutMs: Long = DEFAULT_TIMEOUT_MS): MessageExt? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            val message = received.poll(remaining, TimeUnit.NANOSECONDS) ?: return null
            if (predicate(message)) return message
        }
    }

    override fun close() {
        runCatching { consumer.shutdown() }
    }

    companion object {
        /** 默认等待上限。本机的 broker 与 JVM 共用一台机器，取值要宽松。 */
        const val DEFAULT_TIMEOUT_MS: Long = 30_000

        /** 一次性消费者组的序号，保证同一进程内多次调用也不会撞名。 */
        private val SEQUENCE = AtomicInteger(0)

        /** 生成一个没用过的组名。见类文档。 */
        fun unique(prefix: String): String = "${prefix}_${System.currentTimeMillis()}_${SEQUENCE.incrementAndGet()}"
    }
}

/** 消息体按 UTF-8 解码。线格式是 JSON 文本，断言都要经这一步。 */
fun MessageExt.bodyText(): String = String(body, Charsets.UTF_8)
