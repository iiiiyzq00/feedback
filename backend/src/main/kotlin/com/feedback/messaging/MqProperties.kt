package com.feedback.messaging

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * 消息通道配置。
 *
 * ## 为什么默认关闭而不是「配好就开」
 *
 * 打开这个开关会改变**投递路径**本身：通知与索引的投递从 XXL 轮询换成 broker 驱动，
 * 同时 RCA 领域事件开始离开本进程。这类改动必须是一次显式决定，不能由「配置里恰好有值」触发，
 * 因此默认 `false`，且与 `xxl.job.enabled` 构成双开关（见 [MqTrackReporter] 的启动日志）。
 *
 * ## 连接参数为什么不放进 application.yml 的默认值里
 *
 * `namesrvAddr` 默认指向本机 9876。生产部署必须显式覆盖——默认值只是为了让本机验证
 * 不需要额外配置。它**不是凭据**，因此写进配置文件是允许的：本平台的 broker 目前没有鉴权，
 * 这条通道的安全边界靠「broker 不对外暴露」而不是靠密码，详见
 * `docs/delivery/29-rca-agent-runtime.md` 第 9 节。
 */
@Component
@ConfigurationProperties(prefix = "app.mq")
class MqProperties {

    /** 总开关。默认关闭：不建连接、不起消费者、不投递。 */
    var enabled: Boolean = false

    /** NameServer 地址。逗号分隔可写多个，但本平台只验证过单机形态。 */
    var namesrvAddr: String = "127.0.0.1:9876"

    /**
     * 生产者组前缀，同时是消费者组名的前缀。
     *
     * 消费者组名由 [consumerGroupOf] 拼成 `前缀_主题`：一个主题一个组，因此某个主题上
     * 积压或重投不会拖住另一个主题的进度。**不要**改成「所有主题共用一个组」——
     * 那样 broker 会认为这些订阅是一件事，改一个主题的订阅会影响另一个。
     */
    var groupPrefix: String = "feedback_platform"

    /** 同步发送超时（毫秒）。发件箱会重发，所以这里不需要等太久。 */
    var sendTimeoutMs: Long = 3_000

    /**
     * 新消费者组从队列头部还是尾部开始。
     *
     * 默认 `false`（尾部，`CONSUME_FROM_LAST_OFFSET`）：新部署的消费者不该把主题里
     * 历史积压重放一遍。这在本平台是安全的，因为**库才是事实来源**——
     * 一条没被消费到的消息对应的行会停在待投递状态，由中继重新发出去，不会丢。
     *
     * 本机验证时设成 `true`（`CONSUME_FROM_FIRST_OFFSET`）：端到端用例不该依赖
     * 「先起消费者再发消息」这种启动顺序。
     */
    var consumeFromFirstOffset: Boolean = false

    /** 每个消费者的消费线程上限。本机是多台测试 JVM 共用一台机器，默认值刻意压得低。 */
    var consumerThreads: Int = 4

    /** 重投次数上限，超过后 broker 把消息移进 `%DLQ%<组名>`。 */
    var maxReconsumeTimes: Int = 5

    /** 中继单轮最多取多少条待投递记录。 */
    var relayBatchSize: Int = 200

    /** RCA 领域事件的主题。 */
    var rcaDomainEventTopic: String = "FEEDBACK_RCA_DOMAIN_EVENT"

    /** 告警通知发件箱的主题。 */
    var notificationTopic: String = "FEEDBACK_ALERT_NOTIFICATION"

    /** 派生索引发件箱的主题。 */
    var indexSyncTopic: String = "FEEDBACK_INDEX_OUTBOX"

    /** 主题对应的消费者组名。 */
    fun consumerGroupOf(topic: String): String = "${groupPrefix}_$topic"

    /** 生产者组名。本平台是同一批 JVM 里发、也收，所以组名只需与消费者组不重名。 */
    fun producerGroup(): String = "${groupPrefix}_producer"

    /**
     * 返回启用前的全部配置缺口，内容只包含稳定错误码。
     *
     * 只在开关打开时才校验：关闭时缺值不是错误，而是「没打算跑」。
     * 主题名与组名在这里校验而不是等第一次发送失败，是因为 RocketMQ 对它们的非法字符
     * 报错发生在发送/订阅调用里，那时错误信息里只有主题名，看不出是配置写错还是 broker 拒绝。
     */
    fun readinessErrors(): List<String> = buildList {
        if (!enabled) {
            return@buildList
        }
        if (namesrvAddr.isBlank()) {
            add("MQ_NAMESRV_ADDR_MISSING")
        }
        if (!NAME_PATTERN.matches(groupPrefix)) {
            add("MQ_GROUP_PREFIX_INVALID")
        }
        if (sendTimeoutMs !in 100..60_000) {
            add("MQ_SEND_TIMEOUT_INVALID")
        }
        if (consumerThreads !in 1..64) {
            add("MQ_CONSUMER_THREADS_INVALID")
        }
        if (maxReconsumeTimes !in 0..16) {
            add("MQ_MAX_RECONSUME_INVALID")
        }
        if (relayBatchSize !in 1..1_000) {
            add("MQ_RELAY_BATCH_SIZE_INVALID")
        }
        topics().filterNot(NAME_PATTERN::matches).forEach { topic ->
            add("MQ_TOPIC_INVALID:$topic")
        }
    }

    /** 判断配置是否满足全部启用条件，不执行网络或数据库访问。 */
    fun ready(): Boolean = readinessErrors().isEmpty()

    /** 三个主题的当前取值，供就绪性校验与启动日志共用。 */
    fun topics(): List<String> = listOf(rcaDomainEventTopic, notificationTopic, indexSyncTopic)

    /** 启动时记录一次。只记主题与地址，不记任何凭据（本配置里也没有凭据）。 */
    @PostConstruct
    fun logReadiness() {
        logger.info(
            "MQ configuration: enabled={}, namesrvAddr={}, groupPrefix={}, topics={}, consumeFromFirstOffset={}, ready={}, errors={}",
            enabled, namesrvAddr, groupPrefix, topics(), consumeFromFirstOffset, ready(), readinessErrors()
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MqProperties::class.java)

        /**
         * RocketMQ 对主题名与消费者组名的字符集约束（见 `TopicValidator` /
         * `MixAll.isTopicOrGroupIllegal`）。这里用同一套规则**在启动时**判定，
         * 因此不合法的名字会在就绪性检查阶段暴露，而不是在第一次发送时。
         */
        val NAME_PATTERN: Regex = Regex("^[%|a-zA-Z0-9_-]{1,127}$")
    }
}
