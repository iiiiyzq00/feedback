package com.feedback.messaging

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 启动时说明当前走的是哪条投递轨。
 *
 * ## 为什么需要这么一个「什么都不做」的类
 *
 * 本平台对通知与索引的投递有**两条轨**：XXL 轮询（默认）与 broker 消费。
 * 两条轨都在代码里存在，且开关默认关闭，于是「这次启动到底走哪条」只体现在两个布尔值上。
 * 只看配置文件回答不了这个问题——`application.yml` 里的默认值、环境变量覆盖、
 * 以及 `xxl.job.enabled` 是不是真的打开了，三者要合起来看。
 *
 * 排查「消息没发出去」时第一个要回答的就是这个，所以在这里把它印成一行日志。
 * 这不是诊断日志，而是**启动横幅**：同一次启动只打一条，且不随业务量增长。
 *
 * ## 关于「MQ 打开但 XXL 关闭」
 *
 * 这个组合**不是**错误，而是 MQ 轨的完全体：投递由 broker 驱动，XXL 不再参与。
 * 反过来「两个都关」也是合法部署（纯本地开发，不投递任何东西）。
 * 真正需要人留意的是「MQ 打开但配置不完整」——那由
 * `MqProperties.readinessErrors()` 报出来，这里只原样带上一份便于对照。
 */
@Component
class MqTrackReporter(
    private val properties: MqProperties,
    @param:Value("\${xxl.job.enabled:false}") private val xxlJobEnabled: Boolean
) {

    @PostConstruct
    fun reportTrack() {
        val track = when {
            !properties.enabled && !xxlJobEnabled -> "无（两个开关都关闭，不投递也不调度）"
            !properties.enabled -> "XXL 轮询（app.mq.enabled=false）"
            xxlJobEnabled -> "MQ 消费（app.mq.enabled=true；XXL 仍在跑，但两套投递 handler 已空转）"
            else -> "MQ 消费（app.mq.enabled=true，且未启用 XXL 调度）"
        }
        logger.info(
            "投递轨：{}。app.mq.enabled={} xxl.job.enabled={} topics={} errors={}",
            track, properties.enabled, xxlJobEnabled, properties.topics(), properties.readinessErrors()
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MqTrackReporter::class.java)
    }
}
