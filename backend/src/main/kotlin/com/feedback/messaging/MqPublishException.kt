package com.feedback.messaging

/**
 * 投递失败。
 *
 * 唯一的语义是「这条消息**没有**送达 broker」。调用方（发件箱中继）必须据此保持
 * 「待投递」状态并稍后重试，绝不能把它当成发送成功去标记已投递——
 * 标记之后再也不会有人重发它，那就是发件箱模式要消灭的静默丢失。
 *
 * [reasonCode] 是稳定错误码，允许出现在日志与监控里；[message] 是给人读的说明。
 */
class MqPublishException(
    val reasonCode: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException("$reasonCode: $message", cause)
