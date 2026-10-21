package com.feedback.messaging

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 关闭态的消息通道装配。
 *
 * ## 为什么要显式提供一个「会拒绝」的 Bean，而不是让 [MessagePublisher] 缺席
 *
 * 让 Bean 缺席看起来更干净，但后果是所有注入它的类都要么变成 `ObjectProvider`、
 * 要么加一层 `@ConditionalOnBean`。本平台六个模块共用一套装配，一旦有人漏掉这层条件，
 * 症状是**启动失败**——那还能发现；更糟的是有人为了让它启动成功而给字段一个默认值，
 * 于是开关关闭时投递被静默跳过，业务方以为消息发出去了。
 *
 * 所以这里提供 [MessagePublisher.REFUSING]：开关关闭时，任何**不该发生**的投递动作都会
 * 在第一次调用时立刻抛出 `MQ_DISABLED`，把「配置写错」暴露在日志里而不是埋在数据里。
 *
 * ## 与 [RocketMqMessagePublisher] 的互斥由条件保证
 *
 * 两个 Bean 的条件分别是 `havingValue="true"` 与 `matchIfMissing=true`，
 * 因此在任何配置取值下**恰好有一个**生效，不存在「两个生产者都想启动」的情况。
 */
@Configuration
class MqConfiguration {

    /**
     * 关闭态发布器。开关为 `false` 或缺省时生效，与 `RocketMqMessagePublisher` 互斥。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.mq", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun refusingMessagePublisher(): MessagePublisher = MessagePublisher.REFUSING
}
