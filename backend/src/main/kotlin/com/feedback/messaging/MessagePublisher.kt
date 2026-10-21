package com.feedback.messaging

/**
 * 出站消息端口。
 *
 * ## 失败必须抛，不许返回布尔值
 *
 * 返回 `false` 的实现会让「发失败了」与「发成功了」在调用点长得一样（都被忽略），
 * 于是必然有人写出「无论结果如何都标记已投递」的代码。抛异常把这件事变成不可忽略的：
 * 中继必须显式地捕获它并做出决定（记录失败、保留待投递状态）。
 *
 * ## 实现必须自己负责「重发几次」
 *
 * 本端口不做重试。上游（发件箱中继）掌握「这条记录发了几次、下次什么时候再发」，
 * 在通道层再叠一层重试会让那两个数字互相矛盾。通道层只做一件事：
 * 把这一条**尽力**送出去，送不成就如实报告。
 */
fun interface MessagePublisher {

    /**
     * 发送一条消息。
     *
     * @throws MqPublishException 任何导致这条消息没有送达 broker 的原因。
     */
    fun publish(message: MqMessage)

    companion object {
        /**
         * 关闭态的实现。
         *
         * 它的语义是「**这个部署没有消息通道**」，不是「默认安全」——与
         * `RcaDomainEventJournal.NONE` 同一条纪律。任何在关闭态下走到这里的代码路径
         * 都是配置错误（某个中继忘了先看开关），必须立刻失败。
         * 若在这里静默丢弃，症状会是「开关没开，但业务方以为消息发出去了」。
         */
        val REFUSING: MessagePublisher = MessagePublisher { message ->
            throw MqPublishException(
                "MQ_DISABLED",
                "app.mq.enabled=false，本部署没有消息通道，但仍有代码在向 ${message.topic} 发消息"
            )
        }
    }
}
