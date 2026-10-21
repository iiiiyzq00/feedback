package com.feedback.agent.rca.runtime

import com.feedback.agent.rca.domain.RcaDomainEvent
import com.feedback.agent.rca.domain.RcaDomainEventJournal
import com.feedback.agent.rca.domain.RcaEventType
import com.feedback.agent.rca.domain.RcaNode
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaTaskStatus
import com.feedback.agent.rca.tools.RcaEventIdFactory
import com.feedback.mapper.rca.RcaDomainEventMapper
import com.feedback.repository.impl.rca.MyBatisRcaDomainEventJournalRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
import java.math.BigDecimal
import java.util.UUID

/**
 * 领域事件日志在**真的 MySQL 3307** 上的写入与读回契约。
 *
 * ## 这一条要验的是内存桩验不了的六件事
 *
 * 1. **`INSERT IGNORE` 真的靠 `uk_rca_domain_event_id` 生效**——内存桩里的「撞键」
 *    是本类自己模拟的，真实的唯一键有没有建、建在哪一列上，只有真库知道。
 * 2. **`CAST(? AS JSON)` 真的能写进去**，而且写进去之后 `payload` 是合法的 JSON 列
 *    （拼错一步会让整条 INSERT 报语法错误，而不是静默写进一段文本）。
 * 3. **`chk_rca_domain_event_id` / `chk_rca_domain_event_trace` / `chk_rca_domain_event_version`
 *    真的落在库上**。写入侧今天只有一处实现，这三条约束是唯一防住第二条路径的东西。
 * 4. **`occurred_at` 来自数据库时钟**（`NOW(3)`），而不是某个 JVM 的墙钟：
 *    这一列会与 `t_rca_agent_task.updated_at` 相减来判断「事件在迁移的哪一侧」，
 *    两个时钟相减得到的数字没有意义。
 * 5. **状态迁移与事件真的在同一个事务里**——包括它的反面：回滚时两者都不留。
 *    适配器方法上的 `@Transactional` 有没有真的把两条语句圈进同一个事务，
 *    在内存桩里完全看不出来（单测里注解不生效）。
 * 6. **`listUnpublished` 走的是 `published_at IS NULL` 且有界**，`markPublished` 幂等。
 *    这是 MQ 中继唯一的取件接口，取错一次就是重复投递或永久积压。
 *
 * ## `claim` 那条路径**不**在这里验，理由
 *
 * `RcaTaskLeaseAdapter.claim` 自己挑候选（`lockClaimCandidate` 取 id 最小的可领取行），
 * 于是在这个库里它必然先去领那些既有的夹具任务，而不是本用例刚造的那一行。
 * 强行改库把候选变成自己那一行，就等于在别人的数据上做破坏性前置。
 * 因此本类用 `JdbcTemplate` 手工造出「已领取」的行形状（`RUNNING` + owner + generation），
 * 再驱动 `writeBack` / `releaseForRetry` / `abandon`——这三条都是按 id 精确打靶的。
 * `claim` 发不发事件、发什么，由 `test/` 里的 `RcaTaskLeaseAdapterEventTest` 覆盖。
 *
 * ## 这个类**刻意没有**类级 `@Transactional`
 *
 * 与同目录的 `RcaTaskLeaseContractTest` 相反，本类不把自己的事务套在最外层，理由是
 * 那会让「适配器上的 `@Transactional` 到底存不存在」变成无法观测的事：
 * 外层已经有事务时，注解写没写都进同一个事务，回滚测试照样绿。
 *
 * 代价是本类会**真的提交**——每一行的任务都在自己那条事务里落地，靠
 * [removeCreatedTasks] 按 `task_key` 前缀清掉；事件行随任务外键级联删除。
 * 换来的是两件内存桩与「外层事务」都验不了的事：
 *
 * - **没有外层事务时，`writeBack` 自己开出一条事务并提交**（若注解被删掉，
 *   事件日志的「必须有活动事务」守卫会先抛出来，用例立刻红）；
 * - **外层事务回滚时，状态与事件一起消失**（若事件日志自己开事务，事件会留下来）。
 *
 * 直接调 `journal.record` 的用例必须自己套一层事务（见 [inTransaction]）：
 * 守卫拒绝在无事务时记录事件，这正是它的职责。
 *
 * 读回一律走 `JdbcTemplate`，不走 MyBatis 的同参数二次读——那条路径在同一个事务里
 * 会命中一级缓存，读到改动之前的行（见 `RcaTaskLeaseContractTest` 的类文档）。
 */
@EnabledIfEnvironmentVariable(named = "RCA_ENTRY_TEST_ENABLED", matches = "true")
@SpringBootTest(
    properties = [
        "xxl.job.enabled=false",
        "spring.flyway.enabled=false",
        "spring.main.lazy-initialization=true",
        "app.rca.intake-enabled=false",
        "app.rca.worker-enabled=false"
    ]
)
class RcaDomainEventJournalContractTest @Autowired constructor(
    private val journal: RcaDomainEventJournal,
    private val leases: RcaTaskLeasePort,
    private val eventMapper: RcaDomainEventMapper,
    private val transactionManager: PlatformTransactionManager,
    private val jdbc: JdbcTemplate,
    @Value("\${spring.datasource.url}") private val datasourceUrl: String
) {

    // ---------------------------------------------------------------------
    // 接线
    // ---------------------------------------------------------------------

    @Test
    fun `生产实现真的装进了容器，而不是 NONE`() {
        // 注入不上会直接让整个类起不来（容器里必须恰好有一个实现），所以这条断言真正盯的是
        // 「装进去的是 MyBatis 实现」——若有人为了让某个离线套件跑起来而把 NONE 注册成 Bean，
        // 集成测试会静默地全部变成空转。
        assertTrue(
            journal is MyBatisRcaDomainEventJournalRepository,
            "领域事件端口装的是 ${journal.javaClass.name}，不是生产实现；" +
                "NONE 的语义是「这个部署没有事件日志」，不该出现在生产上下文里"
        )
    }

    // ---------------------------------------------------------------------
    // 写入与读回
    // ---------------------------------------------------------------------

    @Test
    fun `事件写进真实库，读回的字段与写入的一致`() {
        assertLocalPort()
        val taskId = newClaimedTask()

        inTransaction { journal.record(event(taskId)) }

        val row = jdbc.queryForMap(
            "SELECT event_id, aggregate_type, aggregate_id, aggregate_version, event_type, " +
                "task_id, episode_id, trace_id, occurred_at, payload, published_at, publish_attempts " +
                "FROM t_rca_domain_event WHERE task_id = ? ORDER BY id DESC LIMIT 1",
            taskId
        )
        assertEquals(RcaEventIdFactory.of(event(taskId)), row["event_id"], "落库的标识与推导出来的不一致")
        assertEquals(RcaDomainEvent.AGGREGATE_RCA_AGENT_TASK, row["aggregate_type"])
        assertEquals(TASK_VERSION, (row["aggregate_version"] as Number).toLong())
        assertEquals(RcaEventType.TASK_CLAIMED.storageCode, row["event_type"])
        assertEquals(currentEpisodeId, (row["episode_id"] as Number).toLong())
        assertEquals(
            null, row["trace_id"],
            "事件里的 trace_id 兜底了一个值：这一列为空就是空，编一个出来会让" +
                "「这次执行有没有链路标识」永远无法回答"
        )
        // 载荷是 JSON 列，读出来必须仍是合法 JSON 且带着写进去的那个键。
        assertTrue(
            row["payload"].toString().contains("leaseGeneration"),
            "payload 没有按 JSON 写进去，读回的是：${row["payload"]}"
        )
        // 投递列必须完全由中继掌控：领域侧写完就是 NULL / 0。
        assertNull(row["published_at"], "领域侧写入了投递标记：会开出一个「还没发出去就先标成已发」的口子")
        assertEquals(0, (row["publish_attempts"] as Number).toInt())
    }

    @Test
    fun `occurred_at 取自数据库时钟而不是 JVM 墙钟`() {
        assertLocalPort()
        val taskId = newClaimedTask()

        inTransaction { journal.record(event(taskId)) }

        // 判据是「与数据库的 NOW(3) 差多少」，而不是「与 JVM 的 now() 差多少」：
        // 后者在时钟同步的机器上两者都会通过，前者才能证明这一列真的是 SQL 写下的。
        val driftMs = jdbc.queryForObject(
            "SELECT ABS(TIMESTAMPDIFF(MICROSECOND, occurred_at, NOW(3))) / 1000 " +
                "FROM t_rca_domain_event WHERE task_id = ? ORDER BY id DESC LIMIT 1",
            Long::class.java,
            taskId
        )!!
        assertTrue(driftMs < 5_000, "occurred_at 与数据库时钟差了 $driftMs 毫秒，说明它不是 SQL 写的")
    }

    @Test
    fun `同一件事件记两次只留一行`() {
        assertLocalPort()
        val taskId = newClaimedTask()

        // 两次是**两条各自提交的事务**：真实的形状不是「同一个事务里写两遍」，
        // 而是「第一次已提交 → 进程崩溃 → 恢复后重新进入同一个节点」。
        // 拆成两条事务才验得到唯一键在跨事务时也拦得住。
        inTransaction { journal.record(event(taskId)) }
        // 第二次必须静默跳过而不是抛异常，也不该留下第二行——两行内容相同、
        // 标识不同的事件会让人分不清「迁移过两次」与「迁移过一次但记了两遍」。
        inTransaction { journal.record(event(taskId)) }

        assertEquals(
            1, countEvents(taskId, RcaEventType.TASK_CLAIMED),
            "唯一键没有挡住重复写入：历史里出现了两条同一次事实的记录"
        )
    }

    @Test
    fun `同一版本上可以有多条不同的事件`() {
        assertLocalPort()
        val taskId = newClaimedTask()

        // TASK_CLAIMED 与 TASK_RELEASED 都不推进 state_version，所以它们会落在同一个版本上。
        // 这条用例守的是**设计**：库上若存在 (聚合, 版本) 的唯一键，
        // 第二个事件会以「插入失败」告终，而那是正常场景——它在这里必须能写进去。
        inTransaction {
            journal.record(event(taskId))
            journal.record(
                RcaDomainEvent(
                    aggregateType = RcaDomainEvent.AGGREGATE_RCA_AGENT_TASK,
                    aggregateId = taskId,
                    aggregateVersion = TASK_VERSION,
                    type = RcaEventType.TASK_RELEASED,
                    taskId = taskId,
                    episodeId = currentEpisodeId,
                    payload = linkedMapOf("errorCode" to "RCA_TIMEOUT")
                )
            )
        }

        assertEquals(2, countEvents(taskId, null), "同一个版本上写不进第二条事件")
    }

    // ---------------------------------------------------------------------
    // 与状态迁移同一个事务
    // ---------------------------------------------------------------------

    @Test
    fun `没有外层事务时适配器自己开出一条事务并提交`() {
        assertLocalPort()
        val taskId = newClaimedTask()

        // 本类没有类级 @Transactional，所以调用点真的没有任何活动事务。
        // 这条用例的判据不只是「成功了」：事件日志的守卫拒绝在无事务时记录事件，
        // 所以若 RcaTaskLeaseAdapter.writeBack 上的 @Transactional 被删掉，
        // 这里会抛 IllegalStateException 而不是静默地各自提交。
        assertEquals(
            RcaWriteBackResult.APPLIED,
            leases.writeBack(token(taskId, TASK_VERSION), mutation(RcaTaskStatus.RUNNING, RcaNode.RCA_PERSIST)),
            "写回在无外层事务时没有成功：适配器自己那条事务没开起来"
        )

        assertEquals(TASK_VERSION + 1, stateVersionOf(taskId))
        assertEquals(
            1, countEvents(taskId, RcaEventType.TASK_STATE_TRANSITIONED),
            "迁移提交了但事件没落库：两者不在同一条事务里"
        )
    }

    @Test
    fun `中间写回成功后事件与迁移同版本`() {
        assertLocalPort()
        val taskId = newClaimedTask()

        val result = leases.writeBack(
            token(taskId, stateVersion = TASK_VERSION),
            mutation(RcaTaskStatus.RUNNING, RcaNode.RCA_PERSIST)
        )
        assertEquals(RcaWriteBackResult.APPLIED, result)

        assertEquals(
            TASK_VERSION + 1, stateVersionOf(taskId),
            "写回没有推进版本，这条用例验证的就不是「事件与迁移同版本」"
        )
        val row = latestEvent(taskId)
        assertEquals(RcaEventType.TASK_STATE_TRANSITIONED.storageCode, row["event_type"])
        assertEquals(
            TASK_VERSION + 1, (row["aggregate_version"] as Number).toLong(),
            "事件的版本与迁移后的版本对不上：按版本重建历史的人会认为中间丢了一次迁移"
        )
    }

    @Test
    fun `终态写回记 TASK_SETTLED`() {
        assertLocalPort()
        val taskId = newClaimedTask()

        leases.writeBack(
            token(taskId, stateVersion = TASK_VERSION),
            mutation(RcaTaskStatus.NEEDS_HUMAN, nextNode = null, stopReason = RcaStopReason.HUMAN_REQUIRED)
        )

        val row = latestEvent(taskId)
        assertEquals(RcaEventType.TASK_SETTLED.storageCode, row["event_type"])
        assertEquals(TASK_VERSION + 1, (row["aggregate_version"] as Number).toLong())
    }

    @Test
    fun `让出等待重试记 TASK_RELEASED 且版本不推进`() {
        assertLocalPort()
        val taskId = newClaimedTask()

        assertEquals(
            RcaWriteBackResult.APPLIED,
            leases.releaseForRetry(token(taskId, stateVersion = TASK_VERSION), "RCA_TIMEOUT", "itest detail")
        )

        assertEquals(TASK_VERSION, stateVersionOf(taskId), "让出不该推进 state_version")
        val row = latestEvent(taskId)
        assertEquals(RcaEventType.TASK_RELEASED.storageCode, row["event_type"])
        assertEquals(TASK_VERSION, (row["aggregate_version"] as Number).toLong())
        assertTrue(
            row["payload"].toString().contains("RCA_TIMEOUT"),
            "错误码没有进载荷：事后无法从事件上分辨这次让出是哪一类故障"
        )
        assertTrue(
            !row["payload"].toString().contains("itest detail"),
            "错误详情进了会离开本进程的载荷：它可能带上游响应片段"
        )
    }

    @Test
    fun `收口记 TASK_ABANDONED 且留下收口者`() {
        assertLocalPort()
        val taskId = newClaimedTask()

        leases.abandon(
            token(taskId, stateVersion = TASK_VERSION),
            RcaTaskStatus.NEEDS_HUMAN,
            RcaStopReason.HUMAN_REQUIRED,
            "RCA_RECOVERY_UNRESOLVED",
            "itest detail"
        )

        assertEquals(TASK_VERSION + 1, stateVersionOf(taskId))
        val row = latestEvent(taskId)
        assertEquals(RcaEventType.TASK_ABANDONED.storageCode, row["event_type"])
        // 收口把任务行的 lease_owner 清空了，事件里的这一项是「是谁收口的」最后出现的地方。
        assertTrue(
            row["payload"].toString().contains(OWNER),
            "载荷里没有收口者：lease_owner 已被清空，这个信息就此丢失"
        )
        assertNull(ownerOf(taskId), "收口之后任务行上还留着持有者")
    }

    @Test
    fun `写回被 fencing 拒绝时不留事件`() {
        assertLocalPort()
        val taskId = newClaimedTask()

        // 用一个过期的 generation：这正是「租约已被接管，晚到的写回」的形状。
        val stale = token(taskId, stateVersion = TASK_VERSION).copy(generation = 99)
        assertEquals(
            RcaWriteBackResult.FENCED_OUT,
            leases.writeBack(stale, mutation(RcaTaskStatus.RUNNING, RcaNode.RCA_PERSIST))
        )

        assertEquals(0, countEvents(taskId, null), "被拒绝的写回留下了一条事件：那段迁移在世界上没有发生")
    }

    @Test
    fun `事务回滚时状态与事件都不留`() {
        assertLocalPort()
        val taskId = newClaimedTask()
        val versionBefore = stateVersionOf(taskId)

        // 在一条独立的新事务里做写回再整体回滚。这里没有外层事务，所以 REQUIRED 与
        // REQUIRES_NEW 都会新开一条；写 REQUIRES_NEW 是要把「回滚的是这次调用自己开的事务」
        // 这件事写死在用例里，而不依赖本类将来会不会重新套上类级 @Transactional。
        val newTransaction = TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionTemplate.PROPAGATION_REQUIRES_NEW
        }
        newTransaction.execute { status ->
            status.setRollbackOnly()
            leases.writeBack(
                token(taskId, stateVersion = versionBefore),
                mutation(RcaTaskStatus.RUNNING, RcaNode.RCA_PERSIST)
            )
        }

        assertEquals(
            versionBefore, stateVersionOf(taskId),
            "回滚之后状态却推进了：这次写回不在那条被回滚的事务里"
        )
        assertEquals(
            0, countEvents(taskId, null),
            "状态回滚了事件却留下了：库里会多出一段没有发生过的历史——" +
                "这正是「同生共死」这条契约要防的东西"
        )
    }

    // ---------------------------------------------------------------------
    // 待投递扫描（MQ 中继的取件口）
    // ---------------------------------------------------------------------

    @Test
    fun `待投递扫描按写入顺序给出，标记之后不再出现`() {
        assertLocalPort()
        val taskId = newClaimedTask()
        inTransaction {
            journal.record(event(taskId))
            journal.record(event(taskId, type = RcaEventType.TASK_RELEASED))
        }

        val pending = eventMapper.listUnpublished(500)
        val mine = pending.filter { it.taskId == taskId }
        assertEquals(2, mine.size, "未投递扫描没有给出刚写入的事件")
        assertTrue(
            mine[0].id < mine[1].id,
            "未投递扫描没有按 id 升序：中继发送顺序会变得不确定，而订阅方按聚合重建历史时依赖它"
        )

        assertEquals(1, eventMapper.markPublished(mine[0].id), "首次标记没有生效")
        assertEquals(
            0, eventMapper.markPublished(mine[0].id),
            "重复标记又生效了一次：published_at 会被第二次覆盖，「积压了多久」的起点就此改变"
        )
        assertEquals(
            listOf(mine[1].id),
            eventMapper.listUnpublished(500).filter { it.taskId == taskId }.map { it.id },
            "已投递的事件仍然出现在待投递列表里"
        )
        // listByTask 是排障用的读口，顺序必须是 (aggregate_version, id)。
        assertEquals(
            listOf(mine[0].id, mine[1].id),
            eventMapper.listByTask(taskId).map { it.id },
            "按任务读事件的顺序不是实际发生的顺序"
        )
    }

    @Test
    fun `载荷可以按 id 单独取回，且与写入时一致`() {
        assertLocalPort()
        val taskId = newClaimedTask()
        inTransaction { journal.record(event(taskId)) }

        val id = jdbc.queryForObject(
            "SELECT id FROM t_rca_domain_event WHERE task_id = ? ORDER BY id DESC LIMIT 1", Long::class.java, taskId
        )!!
        val payload = eventMapper.selectPayloadById(id)
        assertNotNull(payload, "按 id 取不回载荷：MQ 中继拿不到要发送的内容")
        assertTrue(payload!!.contains("leaseGeneration"), "取回的载荷内容不对：$payload")
    }

    // ---------------------------------------------------------------------
    // 库上的约束
    // ---------------------------------------------------------------------

    @Test
    fun `库上的事件标识格式约束真的在生效`() {
        assertLocalPort()
        val taskId = newClaimedTask()

        // 断言消息里带约束名：只说「抛异常了」时，一个恰好也违反别的约束的值会让用例假绿。
        val thrown = assertThrows(Exception::class.java) {
            jdbc.update(
                "INSERT INTO t_rca_domain_event (event_id, aggregate_type, aggregate_id, aggregate_version, " +
                    "event_type, task_id) VALUES ('NOT-A-HEX-32', ?, ?, 0, 'TASK_CLAIMED', ?)",
                RcaDomainEvent.AGGREGATE_RCA_AGENT_TASK, taskId, taskId
            )
        }
        val messages = generateSequence(thrown as Throwable?) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(
            messages.any { it.contains("chk_rca_domain_event_id") },
            "非法事件标识没有被 chk_rca_domain_event_id 拒掉，实际异常：$messages"
        )
    }

    @Test
    fun `库上的链路标识格式约束真的在生效`() {
        assertLocalPort()
        val taskId = newClaimedTask()

        val thrown = assertThrows(Exception::class.java) {
            jdbc.update(
                "INSERT INTO t_rca_domain_event (event_id, aggregate_type, aggregate_id, aggregate_version, " +
                    "event_type, task_id, trace_id) VALUES (?, ?, ?, 0, 'TASK_CLAIMED', ?, 'NOT-A-HEX-32')",
                "0".repeat(32), RcaDomainEvent.AGGREGATE_RCA_AGENT_TASK, taskId, taskId
            )
        }
        val messages = generateSequence(thrown as Throwable?) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(
            messages.any { it.contains("chk_rca_domain_event_trace") },
            "非法链路标识没有被 chk_rca_domain_event_trace 拒掉，实际异常：$messages"
        )
    }

    // ---------------------------------------------------------------------
    // 测试构件
    // ---------------------------------------------------------------------

    /**
     * 本类没有类级 `@Transactional`，每一行任务都是真提交的，所以清理必须显式做。
     *
     * 只按 `task_key` 前缀删自己的行——`itest-event-` 是本类独有的批次前缀
     * （`docs/delivery/13-demo-runbook.md:70,72` 禁止无批次前缀的 DELETE）。
     * 事件行靠 `fk_rca_domain_event_task` 的 `ON DELETE CASCADE` 跟着走。
     */
    @AfterEach
    fun removeCreatedTasks() {
        jdbc.update("DELETE FROM t_rca_agent_task WHERE task_key LIKE 'itest-event-%'")
    }

    /** 在一个会提交的新事务里跑 [block]：`journal.record` 的守卫要求有活动事务。 */
    private fun <T> inTransaction(block: () -> T): T =
        TransactionTemplate(transactionManager).execute { block() }!!

    /**
     * 最近一次 [newClaimedTask] 借用的 episode。
     *
     * 事件行上的 `episode_id` 是**从令牌搬运**的冗余列，所以它必须等于这一行的来源。
     * 用例靠它来断言「搬运没有串行」——写死一个常量就断不出这件事。
     */
    private var currentEpisodeId: Long = 0

    private fun event(taskId: Long, type: RcaEventType = RcaEventType.TASK_CLAIMED) = RcaDomainEvent(
        aggregateType = RcaDomainEvent.AGGREGATE_RCA_AGENT_TASK,
        aggregateId = taskId,
        aggregateVersion = TASK_VERSION,
        type = type,
        taskId = taskId,
        episodeId = currentEpisodeId,
        traceId = null,
        payload = linkedMapOf("leaseGeneration" to 1L, "attemptCount" to 1, "leaseOwner" to OWNER)
    )

    private fun token(taskId: Long, stateVersion: Long) = RcaLeaseToken(
        taskId = taskId,
        owner = OWNER,
        generation = 1,
        stateVersion = stateVersion,
        episodeId = currentEpisodeId,
        traceId = null
    )

    private fun mutation(
        status: RcaTaskStatus,
        nextNode: RcaNode?,
        stopReason: RcaStopReason? = null
    ) = RcaTaskMutation(
        nextNode = nextNode,
        taskStatus = status,
        stopReason = stopReason,
        stateSnapshot = "{}",
        snapshotSha256 = "0".repeat(64),
        stepsUsed = 0,
        tokensUsed = 0,
        costUsedUsd = BigDecimal.ZERO
    )

    /**
     * 造一行**已领取**的任务。
     *
     * 借用一个 episode（`episode_id` 有外键），`task_key` 带随机后缀避开唯一键，
     * `public_id` 用 `UUID_TO_BIN` 与 `insertRcaTask` 同口径。
     *
     * `lease_generation` 置 1、`lease_owner` 置 [OWNER]、状态置 `RUNNING`：
     * 这正是 `claim` 语句执行之后那一行的形状。不调 `claim` 的理由见类文档
     * ——它会去领夹具任务，而不是本用例刚造的这一行。
     */
    private fun newClaimedTask(): Long {
        val episodeId = jdbc.queryForObject(
            "SELECT id FROM t_feedback_episode ORDER BY id LIMIT 1", Long::class.java
        ) ?: error("库里没有 episode，先跑接入测试（RCA_INTAKE_ENTRY）再跑本测试")
        currentEpisodeId = episodeId
        val taskKey = "itest-event-" + UUID.randomUUID().toString().take(12)
        jdbc.update(
            """
            INSERT INTO t_rca_agent_task (
              public_id, task_key, episode_id, task_status, lease_owner, lease_generation,
              lease_until, deadline_at
            ) VALUES (
              UUID_TO_BIN(?), ?, ?, 'RUNNING', ?, 1, DATE_ADD(NOW(3), INTERVAL 300 SECOND), NULL
            )
            """.trimIndent(),
            UUID.randomUUID().toString(),
            taskKey,
            episodeId,
            OWNER
        )
        // 用 task_key 回查而不是 LAST_INSERT_ID()：本类不在一个事务里跑，
        // JdbcTemplate 两次调用可能落到池里不同的连接上，而 LAST_INSERT_ID() 是连接作用域的。
        return jdbc.queryForObject(
            "SELECT id FROM t_rca_agent_task WHERE task_key = ?", Long::class.java, taskKey
        ) ?: error("刚插入的任务行查不回来：task_key=$taskKey")
    }

    private fun countEvents(taskId: Long, type: RcaEventType?): Int = if (type == null) {
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM t_rca_domain_event WHERE task_id = ?", Int::class.java, taskId
        )!!
    } else {
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM t_rca_domain_event WHERE task_id = ? AND event_type = ?",
            Int::class.java, taskId, type.storageCode
        )!!
    }

    private fun latestEvent(taskId: Long): Map<String, Any?> = jdbc.queryForMap(
        "SELECT event_type, aggregate_version, payload FROM t_rca_domain_event " +
            "WHERE task_id = ? ORDER BY id DESC LIMIT 1",
        taskId
    )

    private fun stateVersionOf(taskId: Long): Long = jdbc.queryForObject(
        "SELECT state_version FROM t_rca_agent_task WHERE id = ?", Long::class.java, taskId
    )!!

    private fun ownerOf(taskId: Long): String? = jdbc.queryForObject(
        "SELECT lease_owner FROM t_rca_agent_task WHERE id = ?", String::class.java, taskId
    )

    /** 3306 是别的项目的库；本测试会写数据，端口判错就是在写别人的库。 */
    private fun assertLocalPort() {
        assertTrue(
            datasourceUrl.contains(":3307"),
            "领域事件契约测试只允许连本机 feedback-platform 的 3307，当前地址端口不对"
        )
    }

    companion object {
        /** 新建任务的 `state_version` 默认值，与 `t_rca_agent_task` 的列默认值一致。 */
        private const val TASK_VERSION = 0L

        private const val OWNER = "itest-event-owner"

        /**
         * 只补**测试专用**的固定密钥，不补数据库地址（理由同 `RcaIntakeEndToEndTest`：
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
