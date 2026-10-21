package com.feedback.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID

/**
 * 通道层对着**真 broker** 的往返：没有 Spring、没有库、没有业务。
 *
 * ## 为什么要单独有这一层
 *
 * 端到端用例（[MqTrackEndToEndTest]）断言的是「某张表的某一行最后变成了什么状态」。
 * 那种断言即使通道层写错了也可能通过——比如 body 里的多字节字符被改坏、或者
 * user property 根本没有随消息走，只要消费侧解析出来的 id 还对，业务断言照样绿。
 * 所以线格式必须有一处**只看字节**的验证，而那个验证不该经过任何业务代码。
 *
 * ## 这个类不连数据库
 *
 * 它刻意不碰 `@SpringBootTest`：起一个大上下文会把「通道能不能用」与
 * 「四条轨各自的业务对不对」两件事绑在一起，而这两件事的失败原因完全不同。
 * 一条消息发不出去的时候，本类能独立地告诉你是通道的问题。
 *
 * ## 每个用例都用**新主题**
 *
 * 主题名带随机后缀、探针用没用过的消费者组：同一台机器上反复跑本类时，
 * 上一次的消息不会出现在这一次的断言里。共用主题会让用例变成「有时通过」。
 */
@EnabledIfEnvironmentVariable(named = "MQ_INTEGRATION_TEST_ENABLED", matches = "true")
class RocketMqBrokerRoundTripTest {

    /**
     * 一条消息从发布到被**独立订阅方**读回，关键字段一个不少。
     *
     * 判据是「探针收到了」而不是「`publish` 没抛异常」：发送成功只说明主节点接下了
     * 这条消息，能被订阅方读到才是「这条通道可用」的定义。
     */
    @Test
    fun `发布的字段能从独立订阅方原样读回`() {
        val suffix = suffix()
        val topic = "FP_MQITEST_RT_$suffix"
        val properties = properties(topic, suffix)
        assertEquals(
            emptyList<String>(), properties.readinessErrors(),
            "用的是真配置类，所以先证明这份配置本身是通过就绪性校验的"
        )

        val publisher = RocketMqMessagePublisher(properties)
        publisher.start()
        try {
            assertTrue(publisher.isRunning(), "生产者启动之后 isRunning 仍然是 false")

            // 主题必须先存在，探针才拿得到路由信息。用一条预热消息把主题建出来——
            // 生产环境靠 `mqadmin updateTopic` 预建，这里用最简单的方式达到同一个效果。
            publisher.publish(
                MqMessage(topic = topic, key = "warmup-$suffix", body = """{"warmup":true}""")
            )

            MqProbe(namesrvAddr(), MqProbe.unique("fp_mqitest_rt_probe"), topic, fromFirstOffset = true).use { probe ->
                probe.start()

                val key = "itest-rt-$suffix"
                val body = """{"text":"中文与 ASCII 混排 / mixed","n":42}"""
                publisher.publish(
                    MqMessage(
                        topic = topic,
                        key = key,
                        body = body,
                        tag = ROUND_TRIP_TAG,
                        headers = mapOf(
                            "eventId" to "evt-$suffix",
                            "aggregateType" to "RCA_AGENT_TASK"
                        )
                    )
                )

                val received = probe.await({ it.keys == key }, AWAIT_MS)
                assertNotNull(received, "${AWAIT_MS}ms 内没有从主题上读回自己发出的消息（key=$key）")
                assertEquals(
                    body, received!!.bodyText(),
                    "正文来回一趟后不一致：多字节字符在编码/解码里被改动了，而库里的正文是中文"
                )
                assertEquals(ROUND_TRIP_TAG, received.tags, "标签没有随消息走")
                assertEquals(key, received.keys, "消息键没有随消息走：同聚合保序依赖它选队列")
                assertEquals("evt-$suffix", received.getUserProperty("eventId"), "自定义属性没有随消息走")
                assertEquals("RCA_AGENT_TASK", received.getUserProperty("aggregateType"))
                assertTrue(
                    !received.msgId.isNullOrBlank(),
                    "broker 没有给出消息 id：事后无法用 mqadmin 把客户端日志与 broker 上的消息对上"
                )
            }
        } finally {
            publisher.stop()
        }
    }

    /**
     * 两个「不该发出去」的入口必须**立刻失败**，而且失败得能读懂。
     *
     * 一条静默丢弃的消息没有告警、没有计数、没有任何痕迹，是这一层最坏的失败方式；
     * 所以未启动就发送、以及超出体积上限，都在发送之前自己判掉。
     */
    @Test
    fun `未启动的生产者与超限的正文都在发送之前失败`() {
        val suffix = suffix()
        val properties = properties("FP_MQITEST_GUARD_$suffix", suffix)
        val publisher = RocketMqMessagePublisher(properties)

        // 刻意不 start()：这正是「Bean 已就绪但连接还没建」那个窗口的形状。
        val notStarted = assertThrows(IllegalStateException::class.java) {
            publisher.publish(MqMessage(topic = properties.rcaDomainEventTopic, key = "1", body = "{}"))
        }
        assertTrue(
            notStarted.message.orEmpty().contains("尚未启动"),
            "报错文本没有说明这是启动顺序问题，读日志的人会去查 broker：${notStarted.message}"
        )

        publisher.start()
        try {
            val tooLarge = assertThrows(IllegalStateException::class.java) {
                publisher.publish(
                    MqMessage(
                        topic = properties.rcaDomainEventTopic,
                        key = "oversized",
                        body = "x".repeat(RocketMqMessagePublisher.MAX_BODY_BYTES + 1)
                    )
                )
            }
            assertTrue(
                tooLarge.message.orEmpty().contains("超过上限"),
                "超限的报错没有说出体积与上限：${tooLarge.message}"
            )
        } finally {
            publisher.stop()
        }
    }

    /**
     * broker 连不上时，调用方看到的必须是 [MqPublishException]，而不是客户端的四类私有异常。
     *
     * 通道层把 `MQClientException` / `RemotingException` / `MQBrokerException` /
     * `InterruptedException` 全部收敛成一个类型，中继因此只需要认一个类型就能正确地
     * 「保持不变、下次重试」。这条断言守的就是那个收敛。
     *
     * ## 为什么要自己改一个系统属性
     *
     * RocketMQ 的 `MQClientInstance` 是按 `clientId = IP@instanceName` 缓存的**进程级单例**。
     * 同一进程里两个生产者的 instanceName 都取默认值 `DEFAULT` 时，第二个会**复用**
     * 第一个已经建好的实例——连同第一个的 namesrvAddr。那样本用例会拿真 nameserver
     * 去发消息、然后一路成功，测的就完全不是「连不上」了。
     * 所以这里给本用例一个独立的 instanceName，用完还原。
     */
    @Test
    fun `broker 连不上时抛的是 MqPublishException`() {
        val suffix = suffix()
        val properties = properties("FP_MQITEST_UNREACHABLE_$suffix", suffix).apply {
            // 刻意指向一个没有 nameserver 的端口。它与 9876 在同一个网段但不同端口，
            // 所以「连接被拒」这一条路径是确定的，不会撞上「端口开着但不是 broker」。
            namesrvAddr = "127.0.0.1:19876"
            sendTimeoutMs = 1_000
        }
        val previous = System.getProperty(SYS_PROP_INSTANCE_NAME)
        System.setProperty(SYS_PROP_INSTANCE_NAME, "fp_mqitest_unreachable_$suffix")
        try {
            val publisher = RocketMqMessagePublisher(properties)
            publisher.start()
            try {
                val thrown = assertThrows(MqPublishException::class.java) {
                    publisher.publish(
                        MqMessage(topic = properties.rcaDomainEventTopic, key = "1", body = "{}")
                    )
                }
                assertEquals("MQ_SEND_FAILED", thrown.reasonCode, "失败原因的稳定错误码变了：${thrown.message}")
            } finally {
                publisher.stop()
            }
        } finally {
            if (previous == null) System.clearProperty(SYS_PROP_INSTANCE_NAME)
            else System.setProperty(SYS_PROP_INSTANCE_NAME, previous)
        }
    }

    private fun properties(topic: String, suffix: String) = MqProperties().apply {
        enabled = true
        namesrvAddr = namesrvAddr()
        groupPrefix = "fp_mqitest_$suffix"
        // 三个主题都指向同一个一次性主题：本类只验通道，不需要三条轨的主题各自存在。
        rcaDomainEventTopic = topic
        notificationTopic = topic
        indexSyncTopic = topic
        sendTimeoutMs = 5_000
    }

    private fun suffix(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    private fun namesrvAddr(): String = System.getenv("MQ_NAMESRV_ADDR")?.takeIf(String::isNotBlank) ?: "127.0.0.1:9876"

    private companion object {
        /** 等一条消息的上限。本机的 broker 与测试 JVM 共用一台机器，取值要宽松。 */
        const val AWAIT_MS: Long = 60_000

        const val ROUND_TRIP_TAG: String = "ROUND_TRIP"

        /** RocketMQ 读实例名用的系统属性（`ClientConfig` 的构造期取值）。 */
        const val SYS_PROP_INSTANCE_NAME: String = "rocketmq.client.name"
    }
}
