package com.feedback.messaging

/**
 * 一条待发送的消息。
 *
 * ## [key] 决定顺序，不只是「便于检索」
 *
 * RocketMQ 用消息 key 的哈希选队列，同一个队列内是追加写的。所以**同一个聚合的消息
 * 必须用同一个 key**，否则同一聚合的两条事件会落进不同队列，消费顺序就与发生顺序无关了。
 * 本平台一律用**任务 id**（而不是事件 id）：聚合是任务，事件是聚合上的事实。
 *
 * key 同时是排障入口，所以它必须是从消息体里能对上的标识，不能是随机串。
 *
 * ## [body] 是完整线格式
 *
 * 载荷由发送方拼好再传入，本类型不做序列化——序列化形状的决定权属于各个中继
 * （见 `agent/rca/runtime/RcaDomainEventRelay`），不属于通道层。通道层只负责搬运。
 *
 * ## [headers] 有保留字
 *
 * RocketMQ 拒绝把 `KEYS` / `TAGS` / `WAIT` 之类的保留名当作用户属性，序列化时直接抛错。
 * 这里不做改名兜底：静默把一个 header 改名或丢掉，会让订阅方读到一个「看起来正常」的
 * 空值，而空值在业务上通常意味着「没有这个信息」——那是另一种事实。
 */
data class MqMessage(
    val topic: String,
    val key: String,
    val body: String,
    val tag: String? = null,
    val headers: Map<String, String> = emptyMap()
)
