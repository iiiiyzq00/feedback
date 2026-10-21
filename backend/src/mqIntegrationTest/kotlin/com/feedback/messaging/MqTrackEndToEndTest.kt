package com.feedback.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.agent.rca.domain.RcaDomainEvent
import com.feedback.agent.rca.domain.RcaDomainEventJournal
import com.feedback.agent.rca.domain.RcaEventType
import com.feedback.agent.rca.runtime.RcaDomainEventOutbox
import com.feedback.agent.rca.runtime.RcaDomainEventRelay
import com.feedback.agent.rca.tools.RcaEventIdFactory
import com.feedback.service.alert.AlertNotificationEvent
import com.feedback.service.alert.NotificationDispatcher
import com.feedback.service.alert.NotificationMqTrack
import com.feedback.service.alert.NotificationWorkerScheduler
import com.feedback.service.sync.DerivedIndexSyncScheduler
import com.feedback.service.sync.IndexMqTrack
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 三条投递轨在**真 broker + 真库（本机 3307）**上的端到端验证。
 *
 * ## 每一条轨都验的是同一件事：状态推进是**经 broker** 发生的
 *
 * 「中继认领 → 发到主题 → 本进程的消费者收到 → 消费侧把状态写回去」——
 * 这条链上任何一环断掉，行都会停在中间状态（`PROCESSING` / `SENT`）。
 * 因此本类的断言一律落在**行最终到了哪个状态**，而不是「某个方法被调用过」：
 * 后者在内存桩里就能验，而内存桩验不出主机的 broker 有没有真的转发。
 *
 * ## 为什么要用 `CONSUME_FROM_FIRST_OFFSET`
 *
 * 消费者是在容器启动时订阅的，而本类的消息是在那之后才发的。默认的
 * `CONSUME_FROM_LAST_OFFSET` 把「新组的起点」定在**启动那一刻的队尾**，
 * 于是「消费者还没完成 rebalance、消息已经发出去了」会变成偶发丢消息。
 * 从队首开始读让这个时序问题不存在：主题上只要有一条消息，消费侧一定能看到它。
 * 代价是同一个组名第二次运行时会把历史消息重放一遍——三条件用的处理器都幂等，
 * 重放只会多打几行 DEBUG/INFO，不会改动任何状态（见 [MqMessageHandler] 的契约）。
 *
 * ## 空转那两条是**断言**，不是省略
 *
 * 用户选定的双轨口径是「切到 MQ 时轮询 handler 变空转」。空转如果只是
 * 「反正没人调度它」，那么一旦有人误开了调度，两条轨会同时认领同一批行——
 * 那不是重复投递，而是**漏投**（认领方不投递，投递方认领不到）。
 * 所以本类显式调用那两个轮询入口，断言它们连一行都不碰。
 *
 * ## 这个类会**真的提交**，清理按批次前缀做
 *
 * 没有类级 `@Transactional`：投递要发生在**事务外面**（发件箱模式的全部意义），
 * 把整类套进一个事务会让「提交之后消费侧才看得到」这件事无法成立。
 * 清理见 [removeCreatedRows]，只按本类独有的批次前缀删。
 */
@EnabledIfEnvironmentVariable(named = "MQ_INTEGRATION_TEST_ENABLED", matches = "true")
@SpringBootTest(
    properties = [
        "xxl.job.enabled=false",
        "spring.flyway.enabled=false",
        // 与仓库里其余每一个全上下文用例一致。**不是**为了让 MQ 那两条轨「不用真的启动」：
        // SmartLifecycle 由 `DefaultLifecycleProcessor` 在容器 refresh 时强制实例化并 start，
        // 懒初始化管不到它，所以下面的 `assertTrue(lifecycle.isRunning())` 仍然是真的断言
        // （`isRunning()` 读的就是 `start()` 里置上的那个标志）。
        //
        // 反过来，本类**实测过 `lazy-initialization=false` 在本仓库起不来**，两个独立原因：
        // 1. 饿汉实例化会去读只有部署环境才有的占位符（`${IMPORT_RAW_STORAGE_SECRET}` 等），
        //    报 `PlaceholderResolutionException`——本仓库没有任何 test 资源文件提供它们；
        // 2. 同一次尝试里还出现过 `RedisConfig.stringRedisTemplate` 与
        //    `RedisAutoConfiguration` 的同名 bean 定义冲突（`BeanDefinitionOverrideException`）。
        //    两次 lazy=false 运行给出的是这两个不同的根因，说明它与扫描顺序有关而不稳定；
        //    本仓库既有的三个 MySQL 全上下文用例对此用的是
        //    `spring.main.allow-bean-definition-overriding=true`。
        // 这两件事都与本类的验证目标（消息有没有经 broker 走一趟）无关，故不在此处改造它们。
        "spring.main.lazy-initialization=true",
        "app.rca.intake-enabled=false",
        "app.rca.worker-enabled=false",
        // 这里以前有一行 `app.rca.worker-id=itest-mq-worker`，注释写的是「开关关着
        // 不足以让 worker bean 构造得出来」。那句话描述的是当时的真实缺陷，但**根因不在
        // 本类**：`rcaWorker` 曾是无条件 `@Bean`，它的构造期校验让两个开关全关的部署
        // 也起不来（生产 `bootRun` 会直接 `Failed to instantiate [RcaWorker]`）。
        // 2026-10-09 改成条件装配后（见 `config/RcaRuntimeConfiguration.kt`），
        // 执行开关关着时那个 Bean 根本不存在，本行随之失去意义并删除——
        // 留着它反而会让那个缺陷重新变成「本类必须配的东西」。
        "app.mq.enabled=true",
        "app.mq.consume-from-first-offset=true",
        // 本机 glibc 2.31 跑不了 DJL 的 native 分词器，BGE-M3 那条路在本机起不来；
        // 索引轨用的实体在桶里根本不存在，所以嵌入根本不会被调用——这里只是让上下文能起。
        "app.ai.embedding.provider=hashing",
        "app.ai.llm.api-key=",
        "app.ai.llm.model="
    ]
)
class MqTrackEndToEndTest @Autowired constructor(
    private val properties: MqProperties,
    private val publisher: MessagePublisher,
    private val lifecycle: RocketMqMessageConsumerLifecycle,
    private val handlers: List<MqMessageHandler>,
    private val rcaRelay: RcaDomainEventRelay,
    private val rcaOutbox: RcaDomainEventOutbox,
    private val journal: RcaDomainEventJournal,
    private val notificationTrack: NotificationMqTrack,
    private val notificationWorker: NotificationWorkerScheduler,
    private val dispatcher: NotificationDispatcher,
    private val indexTrack: IndexMqTrack,
    private val indexScheduler: DerivedIndexSyncScheduler,
    private val jdbc: JdbcTemplate,
    private val transactionManager: PlatformTransactionManager,
    @param:Value("\${mq.integration.db.url:\${spring.datasource.url}}") private val datasourceUrl: String
) {

    private val json = ObjectMapper()

    /** 本类建出来的通知行号，供 [removeCreatedRows] 精确删除。 */
    private val createdNotificationIds = ConcurrentLinkedQueue<Long>()

    private val owners = mutableListOf<AutoCloseable>()

    // ---------------------------------------------------------------------
    // 接线
    // ---------------------------------------------------------------------

    @Test
    fun `MQ 模式下生产者与消费者真的起来了，两条消费轨都已装配`() {
        assertLocalPort()
        assertTrue(
            publisher is RocketMqMessagePublisher,
            "容器里装的发布口是 ${publisher.javaClass.name}：不是 RocketMQ 实现就说明 MQ 模式没有真的生效，" +
                "中继会全部在 REFUSING 上失败（或者更糟——静默地什么都不做）"
        )
        assertTrue(
            lifecycle.isRunning(),
            "消费者生命周期没有启动：消息会发到主题上但本进程没有任何人取，三条轨会全部停在中间状态"
        )
        val topics = handlers.map { it.topic }.toSet()
        assertTrue(properties.notificationTopic in topics, "通知主题上没有消费者")
        assertTrue(properties.indexSyncTopic in topics, "索引主题上没有消费者")
        assertTrue(
            properties.rcaDomainEventTopic !in topics,
            "本进程订阅了 RCA 领域事件主题：那个主题是给**进程外**订阅方用的，" +
                "本进程订阅它等于自己发自己收，会让「事件已经离开本进程」这件事变得无法证明"
        )
    }

    // ---------------------------------------------------------------------
    // RCA 领域事件轨
    // ---------------------------------------------------------------------

    @Test
    fun `领域事件经 broker 到达进程外订阅方，并只在发送成功之后才标记已投递`() {
        assertLocalPort()
        val suffix = suffix()
        val taskId = newClaimedTask(suffix)
        val event = RcaDomainEvent(
            aggregateType = RcaDomainEvent.AGGREGATE_RCA_AGENT_TASK,
            aggregateId = taskId,
            // 领取不推进 state_version，所以这里用 0 与任务行的初值一致。
            aggregateVersion = 0L,
            type = RcaEventType.TASK_CLAIMED,
            taskId = taskId,
            episodeId = currentEpisodeId,
            traceId = TRACE_ID,
            payload = linkedMapOf("leaseGeneration" to 1L, "leaseOwner" to OWNER)
        )
        inTransaction { journal.record(event) }
        val eventId = RcaEventIdFactory.of(event)

        // 探针必须在**中继之前**起来：它要读的是「这条事件发出去了没有」。
        val probe = newProbe(MqProbe.unique("fp_mqitest_rca_probe"), properties.rcaDomainEventTopic)
        rcaRelay.relayPending()

        val received = probe.await({ it.bodyText().contains(eventId) }, AWAIT_MS)
        assertNotNull(
            received,
            "${AWAIT_MS}ms 内没有在 ${properties.rcaDomainEventTopic} 上看到这次事件（eventId=$eventId）：" +
                "事件落了库但没有离开本进程"
        )
        val body = json.readTree(received!!.bodyText())
        assertEquals(eventId, body["eventId"].asText(), "事件标识在线上被改动了：订阅方的幂等键就是它")
        assertEquals(taskId, body["aggregateId"].asLong())
        assertEquals(0L, body["aggregateVersion"].asLong())
        assertEquals(RcaEventType.TASK_CLAIMED.storageCode, body["eventType"].asText())
        assertEquals(TRACE_ID, body["traceId"].asText(), "trace_id 没有随事件离开本进程")
        assertEquals(
            taskId.toString(), received.keys,
            "消息键不是聚合标识：同聚合保序依赖它选队列，取事件标识会让同一聚合的事件散到不同队列"
        )
        assertEquals(RcaEventType.TASK_CLAIMED.storageCode, received.tags)
        assertEquals(eventId, received.getUserProperty("eventId"))
        // 载荷必须是**对象**而不是被二次编码的字符串：订阅方对 payload 的处理是一致的（当对象读字段）。
        assertTrue(
            !body["payload"].isTextual && body["payload"]["leaseGeneration"].asLong() == 1L,
            "载荷被二次编码了：订阅方收到的会是一段还得自己再解一次的字符串，实际是 ${body["payload"]}"
        )

        // 投递标记必须由中继在**发送成功之后**写下：领域侧写完时它是 NULL。
        assertNotNull(publishedAtOf(eventId), "事件已经出现在主题上，但 published_at 仍然是 NULL")
        assertEquals(0, publishAttemptsOf(eventId), "投递成功却记了一次失败")
        assertTrue(
            rcaOutbox.listUnpublished(500).none { it.eventId == eventId },
            "已经投递的事件仍然出现在待投递列表里：下一次中继会把它再发一遍"
        )

        // 再跑一轮：已经投递的事件不该被碰第二次。published_at 被覆盖会让「积压了多久」的起点变化。
        val marked = publishedAtOf(eventId)
        rcaRelay.relayPending()
        assertEquals(marked, publishedAtOf(eventId), "重复中继覆盖了 published_at")
    }

    // ---------------------------------------------------------------------
    // 通知轨
    // ---------------------------------------------------------------------

    @Test
    fun `通知在 MQ 模式下由 broker 驱动投递，正文一次都没有离开数据库`() {
        assertLocalPort()
        val suffix = suffix()
        val instanceId = newAlertInstanceId()
        val event = alertEvent(suffix, instanceId)

        dispatcher.dispatch(event, ruleId = 1L, channels = listOf("IN_APP"))
        val rows = openNotificationRows(instanceId).also { createdNotificationIds.addAll(it.keys) }
        val inAppId = rows.entries.single { it.value == "IN_APP" }.key
        val feishuId = rows.entries.single { it.value == "FEISHU_CARD" }.key

        // 轮询轨在 MQ 模式下必须**整轨空转**：认领会把行标成 SENT，
        // 而 MQ 轨不会去投一条它没发过号的行——结果是这条通知在库里显示「已发送」却从没到达任何人。
        notificationWorker.scheduledDeliver()
        assertEquals("PENDING", statusOf(inAppId), "轮询轨没有空转：它认领了记录")

        val probe = newProbe(MqProbe.unique("fp_mqitest_notify_probe"), properties.notificationTopic)
        notificationTrack.relayPending()

        val received = probe.await({ bodyIdOrNull(it.bodyText()) == inAppId }, AWAIT_MS)
        assertNotNull(received, "${AWAIT_MS}ms 内没有在 ${properties.notificationTopic} 上看到通知 $inAppId 的投递消息")
        assertEquals(
            """{"id":$inAppId}""", received!!.bodyText(),
            "通知正文离开了数据库：线格式必须只有行号，正文由消费侧回库取"
        )
        assertEquals("NOTIFICATION", received.tags, "标签里不该出现渠道名——渠道取值直接对应收件人配置")
        assertEquals(inAppId.toString(), received.keys)

        // 消费侧：IN_APP 不需要任何网络，所以这条必须稳稳定地到 DELIVERED。
        assertTrue(
            awaitUntil(AWAIT_MS) { statusOf(inAppId) == "DELIVERED" },
            "${AWAIT_MS}ms 内通知 $inAppId 没有到达 DELIVERED，当前状态 ${statusOf(inAppId)}"
        )
        assertEquals("IN_APP", providerCodeOf(inAppId), "投递结果里没有写回渠道受理标识")
        // FEISHU_CARD 那条也必须在同一条轨上被处理过（本机没有配 webhook，所以它的结果一定是失败，
        // 这里只断言「不再是 PENDING」——失败的具体分类取决于部署配置，不该由用例钉死）。
        assertTrue(
            awaitUntil(AWAIT_MS) { statusOf(feishuId) != "PENDING" },
            "FEISHU_CARD 那条通知仍然停在 PENDING：中继没有把同一批里的第二条发出去"
        )
    }

    // ---------------------------------------------------------------------
    // 索引轨
    // ---------------------------------------------------------------------

    @Test
    fun `索引任务由 broker 驱动执行，且写回带的是中继那次认领的栅栏令牌`() {
        assertLocalPort()
        val suffix = suffix()
        // 桶里不存在的簇标识：`DerivedIndexSyncWorker.upsert` 在取不到簇行时直接返回，
        // 于是这条断言落在**通道**上（认领 → 发消息 → 消费 → 带栅栏写回），
        // 不会因为本机 ES/Milvus 的状态而变成「有时通过」。
        val missingClusterId = MISSING_CLUSTER_ID_BASE + (1..999).random()
        val taskKey = "itest-mq-index-$suffix"
        val snapshot = json.writeValueAsString(
            linkedMapOf(
                "entityType" to "CLUSTER",
                "entityId" to missingClusterId,
                "operation" to "UPSERT",
                "vectorMode" to "VECTOR_OFF"
            )
        )
        jdbc.update(
            "INSERT INTO t_ai_task(record_type,source_id,task_key,task_status,input_snapshot," +
                "snapshot_retention_until,next_retry_at) " +
                "VALUES('INDEX_OUTBOX',NULL,?,'PENDING',CAST(? AS JSON),DATE_ADD(NOW(3),INTERVAL 30 DAY),NOW(3))",
            taskKey, snapshot
        )
        val taskId = jdbc.queryForObject(
            "SELECT id FROM t_ai_task WHERE task_key = ?", Long::class.java, taskKey
        ) ?: error("刚插入的索引任务查不回来：task_key=$taskKey")

        // 轮询轨必须空转：两条轨同时认领会让后认领的那次把先认领的 fencing 掉，
        // 已完成的结果被丢弃、任务回到待同步——「重复做一遍」只是浪费，白做才是问题。
        indexScheduler.sync()
        assertEquals("PENDING", taskStatusOf(taskId), "轮询轨没有空转：它认领了任务")
        assertEquals(0L, leaseGenerationOf(taskId), "轮询轨推进了栅栏代次")

        val probe = newProbe(MqProbe.unique("fp_mqitest_index_probe"), properties.indexSyncTopic)
        indexTrack.relayPending()

        val received = probe.await({ bodyIdOrNull(it.bodyText()) == taskId }, AWAIT_MS)
        assertNotNull(received, "${AWAIT_MS}ms 内没有在 ${properties.indexSyncTopic} 上看到任务 $taskId 的消息")
        val body = json.readTree(received!!.bodyText())
        assertEquals("CLUSTER", received.tags, "标签没有取自快照里的实体类型")
        assertEquals(taskId.toString(), received.keys)
        assertEquals(taskId.toString(), received.getUserProperty("outboxId"))
        // 中继发出去的是**完整认领结果**：消费者不重新认领，否则栅栏令牌会换来换去。
        assertEquals(1L, body["leaseGeneration"].asLong(), "消息里的栅栏代次不是这次认领的代次")
        assertTrue(body["leaseHolder"].asText().isNotBlank(), "消息里没有携带持有者")
        assertEquals(0, body["retryCount"].asInt())
        // 快照按**转义字符串**发（见 IndexMqTrack 的说明），但解回来必须还是那份快照。
        assertTrue(body["inputSnapshot"].isTextual, "快照被当成了嵌套对象：内部形状会被固化成对外契约")
        val snapshotInMessage = json.readTree(body["inputSnapshot"].asText())
        assertEquals("CLUSTER", snapshotInMessage["entityType"].asText())
        assertEquals(missingClusterId, snapshotInMessage["entityId"].asLong())

        // 消费侧走完之后行必须离开 PROCESSING：要么 COMPLETED（本用例的确定路径），
        // 要么被自己判失败并退回待重试——两者都说明写回真的落到了库上。
        assertTrue(
            awaitUntil(AWAIT_MS) { taskStatusOf(taskId) == "COMPLETED" },
            "${AWAIT_MS}ms 内索引任务 $taskId 没有到达 COMPLETED，当前状态 ${taskStatusOf(taskId)}，" +
                "错误摘要 ${errorDetailOf(taskId)}"
        )
        assertNull(leaseHolderOf(taskId), "完成后持有者没有清空")
        assertNotNull(completedAtOf(taskId), "完成后没有写 completed_at")
    }

    // ---------------------------------------------------------------------
    // 测试构件
    // ---------------------------------------------------------------------

    /**
     * 清理只按本类独有的批次前缀做（`docs/delivery/13-demo-runbook.md:70,72`
     * 禁止无批次前缀的 DELETE / 清库）。
     *
     * 顺序是有讲究的：通知的逐次投递证据（`t_alert_occurrence`）不挂外键，
     * 所以必须显式先删，否则库里会留下一堆指向已经不存在的通知行的事件。
     */
    @AfterEach
    fun removeCreatedRows() {
        owners.forEach { runCatching { it.close() } }
        owners.clear()
        val ids = createdNotificationIds.toList()
        createdNotificationIds.clear()
        if (ids.isNotEmpty()) {
            val placeholders = ids.joinToString(",") { "?" }
            jdbc.update("DELETE FROM t_alert_occurrence WHERE notification_id IN ($placeholders)", *ids.toTypedArray())
            jdbc.update("DELETE FROM t_alert_notification WHERE id IN ($placeholders)", *ids.toTypedArray())
        }
        jdbc.update("DELETE FROM t_ai_task WHERE record_type='INDEX_OUTBOX' AND task_key LIKE 'itest-mq-index-%'")
        // 事件行随任务外键（fk_rca_domain_event_task 是 ON DELETE CASCADE）一起走。
        jdbc.update("DELETE FROM t_rca_agent_task WHERE task_key LIKE 'itest-mq-%'")
    }

    private fun newProbe(group: String, topic: String): MqProbe =
        MqProbe(properties.namesrvAddr, group, topic, fromFirstOffset = true)
            .also { it.start(); owners += it }

    /** 最近一次 [newClaimedTask] 借用的 episode：事件行上的 `episode_id` 必须等于这一行的来源。 */
    private var currentEpisodeId: Long = 0

    /**
     * 造一行**已领取**的任务夹具。
     *
     * 不调 `claim`：它会去领库里既有的夹具任务，而不是本用例刚造的这一行（同
     * `RcaDomainEventJournalContractTest` 的理由）。这里直接造出 claim 之后那一行的形状。
     */
    private fun newClaimedTask(suffix: String): Long {
        currentEpisodeId = jdbc.queryForObject(
            "SELECT id FROM t_feedback_episode ORDER BY id LIMIT 1", Long::class.java
        ) ?: error("库里没有 episode：先跑 RCA 接入测试再跑本测试")
        val taskKey = "itest-mq-$suffix"
        jdbc.update(
            "INSERT INTO t_rca_agent_task (public_id, task_key, episode_id, task_status, lease_owner, " +
                "lease_generation, lease_until, deadline_at) " +
                "VALUES (UUID_TO_BIN(?), ?, ?, 'RUNNING', ?, 1, DATE_ADD(NOW(3), INTERVAL 300 SECOND), NULL)",
            UUID.randomUUID().toString(), taskKey, currentEpisodeId, OWNER
        )
        return jdbc.queryForObject(
            "SELECT id FROM t_rca_agent_task WHERE task_key = ?", Long::class.java, taskKey
        ) ?: error("刚插入的任务行查不回来：task_key=$taskKey")
    }

    /**
     * 一个**没用过**的告警实例标识。
     *
     * P2 的必需渠道集是 `{FEISHU_CARD, IN_APP}`，所以 dispatch 一次会入两行。
     * 实例标识换成新的，是为了不撞上 `uk_alert_fired_channel_once`——那个唯一键在
     * `event_type='ALERT_FIRED'` 时按 (实例, 渠道) 去重，复用标识会让第二行插不进去。
     */
    private fun newAlertInstanceId(): Long =
        ALERT_INSTANCE_BASE + (1..999_999).random()

    private fun alertEvent(suffix: String, instanceId: Long) = AlertNotificationEvent(
        eventId = "itest-mq-$suffix",
        eventType = "ALERT_FIRED",
        title = "MQ 通道端到端验证",
        summary = "这条通知只用于验证投递轨；它的正文必须留在数据库里",
        severity = "P2",
        status = "FIRING",
        ruleCode = "ITEST_MQ_RULE",
        ruleName = "MQ 验证规则",
        currentValue = 1.0,
        window = "5m",
        instanceId = instanceId,
        firedAt = OffsetDateTime.now().toString()
    )

    private fun inTransaction(block: () -> Unit) {
        TransactionTemplate(transactionManager).execute { block() }
    }

    /** 轮询到条件成立或超时。返回是否成立，**不抛**——调用方要给出带上下文的失败信息。 */
    private fun awaitUntil(timeoutMs: Long, block: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (block()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return block()
    }

    private fun publishedAtOf(eventId: String): String? = jdbc.queryForObject(
        "SELECT CAST(published_at AS CHAR) FROM t_rca_domain_event WHERE event_id = ?", String::class.java, eventId
    )

    private fun publishAttemptsOf(eventId: String): Int = jdbc.queryForObject(
        "SELECT publish_attempts FROM t_rca_domain_event WHERE event_id = ?", Int::class.java, eventId
    )!!

    private fun openNotificationRows(instanceId: Long): Map<Long, String> = jdbc.queryForList(
        "SELECT id, notify_channel FROM t_alert_notification " +
            "WHERE record_type='NOTIFICATION' AND alert_instance_id = ? AND notify_status='PENDING'",
        instanceId
    ).associate { (it["id"] as Number).toLong() to (it["notify_channel"] as String) }

    private fun statusOf(id: Long): String? = jdbc.queryForObject(
        "SELECT notify_status FROM t_alert_notification WHERE id = ?", String::class.java, id
    )

    private fun providerCodeOf(id: Long): String? = jdbc.queryForObject(
        "SELECT last_provider_code FROM t_alert_notification WHERE id = ?", String::class.java, id
    )

    private fun taskStatusOf(id: Long): String? = jdbc.queryForObject(
        "SELECT task_status FROM t_ai_task WHERE id = ?", String::class.java, id
    )

    private fun leaseGenerationOf(id: Long): Long = jdbc.queryForObject(
        "SELECT lease_generation FROM t_ai_task WHERE id = ?", Long::class.java, id
    )!!

    private fun leaseHolderOf(id: Long): String? = jdbc.queryForObject(
        "SELECT lease_holder FROM t_ai_task WHERE id = ?", String::class.java, id
    )

    private fun completedAtOf(id: Long): String? = jdbc.queryForObject(
        "SELECT CAST(completed_at AS CHAR) FROM t_ai_task WHERE id = ?", String::class.java, id
    )

    private fun errorDetailOf(id: Long): String? = jdbc.queryForObject(
        "SELECT error_detail FROM t_ai_task WHERE id = ?", String::class.java, id
    )

    /** 只认「能取出一个数字 id」。线上可能同时有别的消息，断言必须按内容挑。 */
    private fun bodyIdOrNull(body: String): Long? =
        runCatching { json.readTree(body)["id"]?.takeIf { it.isNumber }?.asLong() }.getOrNull()

    private fun suffix(): String = UUID.randomUUID().toString().replace("-", "").take(10)

    /** 3306 是别的项目的库；本测试会写数据，端口判错就是在写别人的库。 */
    private fun assertLocalPort() {
        assertTrue(
            datasourceUrl.contains(":3307"),
            "MQ 端到端测试只允许连本机 feedback-platform 的 3307（当前 $datasourceUrl 端口不对）"
        )
    }

    companion object {
        /** 等消息 / 等状态推进的上限。broker 与测试 JVM 共用一台机器，取值要宽松。 */
        const val AWAIT_MS: Long = 60_000

        private const val POLL_INTERVAL_MS: Long = 200

        private const val OWNER = "itest-mq-owner"

        /** 库上有 `chk_rca_domain_event_trace`，链路标识必须是 32 位十六进制。 */
        private const val TRACE_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        /** 索引任务用的簇标识基数。加到它上面得到的值在本机库里不可能存在。 */
        private const val MISSING_CLUSTER_ID_BASE = 900_000_000L

        private const val ALERT_INSTANCE_BASE = 900_000_000L

        /**
         * 只补**测试专用**的固定密钥，不补数据库地址（同 `RcaIntakeEndToEndTest`：
         * 一个 3306 的兜底会让本测试静默连上别的项目的库）。
         */
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("app.masking.fingerprint-secret") { "test-fingerprint-secret-at-least-32-characters" }
            registry.add("app.masking.contact-storage-secret") { "test-contact-secret-at-least-32-characters" }
            registry.add("dubbo.enabled") { "false" }
        }
    }
}
