package com.feedback.messaging

/**
 * 交给订阅方的一条消息。
 *
 * 这是 [MqMessage] 的接收侧对偶：字段一一对应，但多出 [msgId] 与 [reconsumeTimes] ——
 * 它们由 broker 发出，发送方写不了。排障时这两个值是把「客户端日志」与
 * 「broker 上的消息」对起来的关键（`mqadmin queryMsgById`）。
 */
data class ConsumedMqMessage(
    val topic: String,
    val tag: String?,
    val key: String?,
    val body: String,
    val msgId: String,
    val reconsumeTimes: Int,
    val headers: Map<String, String>
)

/**
 * 订阅方的处理契约。
 *
 * ## 抛异常就是「没有确认」
 *
 * 实现正常返回 ⟹ 客户端回 `CONSUME_SUCCESS`；抛异常 ⟹ 回 `RECONSUME_LATER`，
 * broker 稍后重投。重投次数用尽后消息进入 `%DLQ%<组名>`，**那里没有人看**。
 * 因此「抛异常」这个动作的后果要说清：它换来的是重投，不是告警。
 * 实现里该记的 ERROR 日志必须自己记，否则一条最终进了死信的消息在日志里可以不留下任何痕迹。
 *
 * ## 实现必须幂等
 *
 * 重投、以及「同一批里有一条失败导致整批重来」都会让同一条消息被处理两次以上。
 * 本平台三个订阅方都落在带唯一键的表上（`t_rca_domain_event` 的 `uk_rca_domain_event_id`、
 * 通知发件箱的 `uk_alert_fired_channel_once`、索引发件箱的稳定 task_key），
 * 所以第二次处理会被唯一键挡掉——这是「幂等」在本平台的具体含义，
 * 而不是「大概不会重复所以没关系」。
 *
 * ## 主题与消费者组
 *
 * [topic] 由实现声明。消费者组名由 `MqProperties.consumerGroupOf(topic)` 推出，
 * 一个主题一个组：同一主题上多个订阅方实例会分摊消息（这是横向扩展），
 * 不同主题之间互不影响进度。
 */
interface MqMessageHandler {

    /** 订阅的主题。必须与 `MqProperties` 里配置的某个主题一致。 */
    val topic: String

    /** 处理一条消息。正常返回即确认；抛异常即不确认、等待重投。 */
    fun handle(message: ConsumedMqMessage)
}
