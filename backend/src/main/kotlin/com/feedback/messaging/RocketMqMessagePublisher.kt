package com.feedback.messaging

import org.apache.rocketmq.client.producer.DefaultMQProducer
import org.apache.rocketmq.client.producer.SendStatus
import org.apache.rocketmq.common.message.Message
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets

/**
 * [MessagePublisher] 的 RocketMQ 实现，同时负责生产者的启停。
 *
 * 生产者与生命周期放在同一个类里，是因为「能不能发」与「有没有启动」必须是同一个判断。
 * 拆成两个 Bean 的话，`publish` 会走进一个「Bean 已就绪但连接还没建」的窗口，
 * 那时 `send` 抛的是客户端内部的 `MQClientException: service state not OK`，
 * 读日志的人会以为 broker 有问题。
 *
 * ## 只重试一次，且只重试「换队列」这一类
 *
 * `retryTimesWhenSendFailed = 1`：同步发送失败时换一个队列再试一次。这与发件箱的重发
 * 层次不同——这一层处理的是「这个队列所在的 broker 恰好不可用」，
 * 发件箱处理的是「这条记录还没送达」。把次数调大只会让一次失败等待更久，
 * 而真正需要「等一会儿再试」的场景由发件箱的退避负责。
 *
 * ## 失败一律转成 [MqPublishException]
 *
 * 客户端会抛 `MQClientException` / `RemotingException` / `MQBrokerException` /
 * `InterruptedException` 四类。前两类是「没送出去」，第三类是 broker 明确拒绝，
 * 第四类是线程被中断。全平台只有 [MqPublishException] 一种写法，所以调用方
 * 只需要认这一个类型；原始异常挂在 cause 上，不丢信息。
 */
@Component
@ConditionalOnProperty(prefix = "app.mq", name = ["enabled"], havingValue = "true")
class RocketMqMessagePublisher(
    private val properties: MqProperties
) : MessagePublisher, SmartLifecycle {

    private val log = LoggerFactory.getLogger(RocketMqMessagePublisher::class.java)

    private val producer = DefaultMQProducer(properties.producerGroup()).apply {
        namesrvAddr = properties.namesrvAddr
        retryTimesWhenSendFailed = 1
        // 超过上限时客户端会在发送前就拒绝，错误信息比 broker 侧的更清楚。
        // 上限与 broker 默认的 4MB 对齐，留出余量给协议头与用户属性。
        maxMessageSize = MAX_BODY_BYTES
    }

    @Volatile
    private var running = false

    override fun publish(message: MqMessage) {
        check(running) {
            "生产者尚未启动就在发送消息（topic=${message.topic}）：publish 只应在容器启动完成后被调用"
        }
        val body = message.body.toByteArray(StandardCharsets.UTF_8)
        // 长度在发送前自己判一次：让「这条消息太大」变成一个带主题与 key 的错误，
        // 而不是一条来自 broker 的、只说明「消息体超过限制」的拒绝。
        check(body.size <= MAX_BODY_BYTES) {
            "消息体 ${body.size} 字节超过上限 $MAX_BODY_BYTES（topic=${message.topic} key=${message.key}）：" +
                "应缩小载荷或改走对象存储"
        }
        val payload = Message(message.topic, message.tag.orEmpty(), message.key, body)
        message.headers.forEach { (name, value) -> payload.putUserProperty(name, value) }
        val result = try {
            producer.send(payload, properties.sendTimeoutMs)
        } catch (interrupted: InterruptedException) {
            // 中断标志必须还原：吞掉它会让上层的停机流程永远等不到这次中断。
            Thread.currentThread().interrupt()
            throw MqPublishException(
                "MQ_SEND_INTERRUPTED",
                "发送被中断（topic=${message.topic} key=${message.key}）",
                interrupted
            )
        } catch (error: Throwable) {
            throw MqPublishException(
                "MQ_SEND_FAILED",
                "发送失败（topic=${message.topic} key=${message.key}）：${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                error
            )
        }
        // SEND_OK 之外的三个状态（刷盘超时、从节点超时、从节点不可用）都表示这条消息
        // **可能**已经写进主节点但没达到配置的持久化要求。把它们当失败处理：重发一次的代价
        // 是订阅方收到重复（订阅方幂等），而当成成功处理的代价是这条事件永远不会再被提及。
        if (result.sendStatus != SendStatus.SEND_OK) {
            throw MqPublishException(
                "MQ_SEND_STATUS_${result.sendStatus}",
                "broker 返回 ${result.sendStatus}（topic=${message.topic} key=${message.key}）"
            )
        }
        if (result.msgId.isNullOrBlank()) {
            throw MqPublishException(
                "MQ_SEND_NO_MSG_ID",
                "broker 返回成功但没有消息 id（topic=${message.topic} key=${message.key}）：无法据此对账"
            )
        }
        log.debug(
            "消息已投递：topic={} key={} msgId={} queue={}",
            message.topic, message.key, result.msgId, result.messageQueue?.brokerName
        )
    }

    override fun start() {
        producer.start()
        running = true
        log.info(
            "MQ 生产者已启动：group={} namesrv={}",
            properties.producerGroup(), properties.namesrvAddr
        )
    }

    override fun stop() {
        // 先把发布口关上：停机过程中新来的 publish 应当立刻失败，而不是撞在一个正在关的生产者上。
        running = false
        runCatching { producer.shutdown() }
            .onFailure { log.warn("MQ 生产者关闭失败：{}", it.javaClass.simpleName) }
    }

    override fun isRunning(): Boolean = running

    companion object {
        /** 单条消息体上限（字节）。取值低于 broker 默认的 4MB，余量留给协议头与用户属性。 */
        const val MAX_BODY_BYTES: Int = 3_500_000
    }
}
