package com.feedback.agent.rca.runtime

import com.feedback.config.RcaProperties
import com.feedback.mapper.rca.RcaTaskLeaseMapper
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * `t_rca_agent_task` 上两个**「领取时才补」的列**（`deadline_at` 与 `trace_id`）的
 * 写入与读回契约，跑在真的 MySQL 3307 上。
 *
 * ## 这两列此前一次都没被写过
 *
 * `insertRcaTask`（`EpisodeSessionMapper.xml`）只写四个列，所以两列恒为 NULL：
 * `deadline_at` 为 NULL 让领取条件里的 `(deadline_at IS NULL OR deadline_at > NOW(3))`
 * 恒真、`app.rca.task-timeout-minutes` 配了也没有落点；`trace_id` 为 NULL 让整条
 * 执行链路（Worker → 状态图 → 工具调用）在库里没有任何可关联的标识。
 * 本次改动把两者的写入点都放在**领取时刻**（`claim` 语句里的两个 `IFNULL(...)`），
 * 于是这两列的语义第一次真的存在——因此必须被真库验证，而不是只被内存桩验证：
 *
 * - `IFNULL(...)` 是真的只补一次，还是每次领取都续一个新的；
 * - `NOW(3)` 在同一语句里是不是同一个读数（租约与截止时间必须由同一次读数推出）；
 * - 读回的差值**保不保留符号**（超期要为负，夹到 0 就等于把超期抹掉了）；
 * - NULL 与负数是两件事；NULL 与「有一个值」同样是两件事。
 * - 迁移里的 CHECK 约束真的落到了库上（`chk_rca_agent_trace` 会拒掉非法形状）。
 *
 * 两列的形状相同而**理由不同**，这一点不要合并着看：`deadline_at` 的「只补一次」
 * 管的是超时上限不被重试反复续期，`trace_id` 的「只补一次」管的是同一个任务
 * 跨多次重试仍然是**一条**链路。共同点是它们都必须由**库里那一行**决定，
 * 而不是由某个 Worker 手里的本地状态决定。
 *
 * ## 为什么直接调 mapper 而不是走适配器
 *
 * `RcaTaskLeaseAdapter.claim` 自己挑候选（`lockClaimCandidate` 取 id 最小的可领取行），
 * 这条用例要动的是**指定的那一行**，所以用 mapper 的 `claim(id = ...)` 精确打靶。
 * 适配器那一段（配置 → 语句参数）由 `RcaWorkerEndToEndTest` 在生产路径上覆盖，
 * 那里断言的是「本次领取到的任务，其 `deadline_at` 就是配置里那个值」。
 *
 * ## 形状上的约定
 *
 * 整个类在会回滚的事务里跑：自己造一行任务（借用一个 episode，`episode_id` 有外键），
 * 不需要清理、也不会碰到夹具任务的租约。
 *
 * ## 一个事务里只能读一次 `findClaimed`
 *
 * 这条约定不是风格问题，它是下面那几条读回用例**各自独立**的原因。
 * MyBatis 的一级缓存作用域是 SESSION（本仓库没有改 `localCacheScope`），
 * 而 Spring 事务里的 `SqlSession` 是整条事务复用的：同一个 statement + 同一组参数
 * **第二次调用不会打到数据库**，直接返回上一次的结果行。
 * 用 `JdbcTemplate` 改库不会让 MyBatis 知道（只有走 MyBatis 的 update 才清缓存），
 * 于是「改库 → 再读」这种写法会静默地读到**改动之前**的那一行，
 * 并让断言以「值没变」的形式失败——看起来像 SQL 写错了，实际是缓存。
 * 拆成三个方法之后每个方法只读一次，读到的必然是库上的真实行。
 */
@EnabledIfEnvironmentVariable(named = "RCA_ENTRY_TEST_ENABLED", matches = "true")
@SpringBootTest(
    properties = [
        "xxl.job.enabled=false",
        "spring.flyway.enabled=false",
        "spring.main.lazy-initialization=true",
        "app.rca.intake-enabled=false",
        "app.rca.worker-enabled=false",
        // 与生产默认的 30 不同（11），是刻意的：断言里用的是这个值，
        // 于是「配的是多少就写多少」可验证，而不是被默认值蒙对。
        // 这个字面量与 TIMEOUT_MINUTES 必须一致，由下面的 `配置值真的绑到了属性上` 用例盯着。
        "app.rca.task-timeout-minutes=11"
    ]
)
@Transactional
class RcaTaskLeaseContractTest @Autowired constructor(
    private val mapper: RcaTaskLeaseMapper,
    private val properties: RcaProperties,
    private val jdbc: JdbcTemplate,
    @Value("\${spring.datasource.url}") private val datasourceUrl: String
) {

    // ---------------------------------------------------------------------
    // 配置绑定
    // ---------------------------------------------------------------------

    /**
     * 上面那个字面量、本类的常量、以及生产适配器实际传给语句的值，是同一个数。
     *
     * 缺了这一条，`@SpringBootTest` 的属性写错时整类用例只会「一致地失败」，
     * 而失败信息看起来像是在说 SQL 写错了。
     */
    @Test
    fun `配置值真的绑到了属性上`() {
        assertEquals(
            TIMEOUT_MINUTES, properties.taskTimeoutMinutes,
            "测试属性与用例常量不一致：本类的其余断言会一起失真"
        )
    }

    // ---------------------------------------------------------------------
    // 写入
    // ---------------------------------------------------------------------

    @Test
    fun `首次领取补上截止时间，且与租约同一次时钟读数推出`() {
        assertLocalPort()
        val taskId = newPendingTask()

        assertEquals(1, claim(taskId, OWNER_1))

        // 同一条语句里 NOW(3) 只会被取一次，所以两个剩余量之差是**精确**的：
        // 11 分钟减 180 秒 = 480 秒。用差值而不是各自的绝对值来断言，
        // 是为了让这条用例的结果不随「语句执行到读到结果之间流逝了多少秒」而变。
        val remaining = jdbc.queryForMap(
            """
            SELECT TIMESTAMPDIFF(SECOND, NOW(3), deadline_at) AS until_deadline,
                   TIMESTAMPDIFF(SECOND, NOW(3), lease_until)  AS until_lease
            FROM t_rca_agent_task WHERE id = ?
            """.trimIndent(),
            taskId
        )
        val untilDeadline = (remaining["until_deadline"] as Number).toLong()
        val untilLease = (remaining["until_lease"] as Number).toLong()

        assertEquals(
            TIMEOUT_MINUTES * 60 - LEASE_SECONDS, untilDeadline - untilLease,
            "截止时间与租约不是同一次 NOW(3) 推出的：两次读数会让「租约到期了但还没超期」依赖执行顺序"
        )
        // 绝对值也要核：把分钟误当秒、或者单位算错，差值那一条是看不出来的。
        assertTrue(
            untilDeadline in (TIMEOUT_MINUTES * 60 - 5)..(TIMEOUT_MINUTES * 60),
            "deadline_at 不是「领取时刻 + $TIMEOUT_MINUTES 分钟」，实际剩余 $untilDeadline 秒"
        )
    }

    @Test
    fun `再次领取不会续一个新的截止时间`() {
        assertLocalPort()
        val taskId = newPendingTask()
        assertEquals(1, claim(taskId, OWNER_1))
        val firstDeadline = deadlineOf(taskId)

        // 租约过期才能再领。这里直接改库钟会让 NOW(3) 与真实时间脱节，
        // 于是「第二次领取后 deadline_at 没变」这条断言会因为「两次读到的是同一个 past」
        // 而变得没有意义——把租约推到过去，让第二次领取走真实时间。
        expireLease(taskId)
        assertEquals(1, claim(taskId, OWNER_2))

        assertEquals(
            firstDeadline, deadlineOf(taskId),
            "第二次领取续了新的截止时间：反复崩溃的任务每次被领都重获一个完整配额，" +
                "「任务超时」的实际上限就成了「它崩溃了多少次」"
        )
        assertEquals(2L, generationOf(taskId), "第二次领取没有生效，这条用例验证的不是 IFNULL 分支")
    }

    @Test
    fun `首次领取补上链路标识，形状是 32 位小写十六进制`() {
        assertLocalPort()
        val taskId = newPendingTask()

        assertEquals(1, claim(taskId, OWNER_1))

        // 读回的是**库里已经定下来的那一个**（findClaimed 只读，不生成）。
        // 形状用正则而不是「非空」来断言：32 位小写十六进制是 W3C Trace Context 的口径，
        // 也是 chk_rca_agent_trace 的约束；大写混进来会让同一个标识在两张表上长得不一样。
        val traceId = mapper.findClaimed(taskId, OWNER_1)!!.traceId
        assertTrue(
            traceId != null && traceId.matches(Regex("^[0-9a-f]{32}\$")),
            "领取补上的链路标识不是 32 位小写十六进制：$traceId"
        )
        assertEquals(
            traceId, traceIdOf(taskId),
            "读回的值与库上的不一致：Worker 手里的 trace 与事后按 trace join 的那一个会是两个值"
        )
    }

    @Test
    fun `再次领取不会换一个新的链路标识`() {
        assertLocalPort()
        val taskId = newPendingTask()
        assertEquals(1, claim(taskId, OWNER_1))
        // 两次 findClaimed 的 owner 不同，因此是两条不同的缓存键——
        // 不会撞上类 KDoc 里那条「一个事务里同一 statement + 同参数只读一次」的陷阱。
        val first = mapper.findClaimed(taskId, OWNER_1)!!.traceId

        expireLease(taskId)
        assertEquals(1, claim(taskId, OWNER_2))

        assertEquals(
            first, mapper.findClaimed(taskId, OWNER_2)!!.traceId,
            "第二次领取换了新的链路标识：一个任务崩溃三次就成了三条互不相干的记录，" +
                "而「这个任务重试过几次、每次到哪一步死的」恰恰是排障时唯一要看的东西"
        )
        assertEquals(2L, generationOf(taskId), "第二次领取没有生效，这条用例验证的不是 IFNULL 分支")
    }

    @Test
    fun `库上没写过链路标识时读回 null，而不是编一个`() {
        assertLocalPort()
        val taskId = newPendingTask()
        assertEquals(1, claim(taskId, OWNER_1))
        // 造出「早于该列引入的那一行」的形状。本方法对 findClaimed 只读一次，读到的是库上的真实行。
        jdbc.update("UPDATE t_rca_agent_task SET trace_id = NULL WHERE id = ?", taskId)

        assertNull(
            mapper.findClaimed(taskId, OWNER_1)!!.traceId,
            "库里是 NULL 却被读成了一个值：读侧一旦兜底生成，" +
                "「这次执行到底有没有链路标识」就永远无法回答"
        )
    }

    @Test
    fun `库上的格式约束真的在生效`() {
        assertLocalPort()
        val taskId = newPendingTask()

        // 这条用例盯的是**迁移真的落到了库上**，而不是只落在文件里。
        // 断言消息里带约束名：只说「抛异常了」时，一个恰好也违反别的约束的值会让用例假绿。
        val thrown = assertThrows(Exception::class.java) {
            jdbc.update("UPDATE t_rca_agent_task SET trace_id = 'NOT-A-HEX-32' WHERE id = ?", taskId)
        }
        val messages = generateSequence(thrown as Throwable?) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(
            messages.any { it.contains("chk_rca_agent_trace") },
            "写入非法链路标识没有被 chk_rca_agent_trace 拒掉，实际异常：$messages"
        )
    }

    // ---------------------------------------------------------------------
    // 读回
    // ---------------------------------------------------------------------

    @Test
    fun `未超期的截止时间读回正的剩余毫秒数`() {
        assertLocalPort()
        val taskId = newPendingTask()
        assertEquals(1, claim(taskId, OWNER_1))

        val fresh = deadlineRemainingOf(taskId, OWNER_1)
        assertTrue(
            fresh!! in (TIMEOUT_MINUTES * 60_000 - 5_000)..(TIMEOUT_MINUTES * 60_000),
            "读回的剩余毫秒数与写入的配置对不上：$fresh（期望约 ${TIMEOUT_MINUTES * 60_000}）"
        )
    }

    @Test
    fun `超期的截止时间读回负的剩余毫秒数，而不是夹到 0`() {
        assertLocalPort()
        val taskId = newPendingTask()
        assertEquals(1, claim(taskId, OWNER_1))

        // 把截止时间推到过去：这正是「第一次领取之后崩了、11 分钟后才有人再领」的形状。
        jdbc.update(
            "UPDATE t_rca_agent_task SET deadline_at = DATE_SUB(NOW(3), INTERVAL 5 MINUTE) WHERE id = ?",
            taskId
        )

        val overdue = deadlineRemainingOf(taskId, OWNER_1)
        assertTrue(
            overdue!! < -299_000,
            "超期的剩余时间没有保持符号（实际 $overdue）：夹到 0 就等于把超期抹平成「刚好到期」，Worker 再也分不出两者"
        )
    }

    @Test
    fun `没写过截止时间的任务读回 null，而不是 0`() {
        assertLocalPort()
        val taskId = newPendingTask()
        assertEquals(1, claim(taskId, OWNER_1))
        jdbc.update("UPDATE t_rca_agent_task SET deadline_at = NULL WHERE id = ?", taskId)

        assertNull(
            deadlineRemainingOf(taskId, OWNER_1),
            "库里是 NULL 却被读成 0：一个从未设过截止时间的任务会立刻被判成超期"
        )
    }

    // ---------------------------------------------------------------------
    // 测试构件
    // ---------------------------------------------------------------------

    /**
     * 造一行 `PENDING` 任务。
     *
     * `public_id` 用 `UUID_TO_BIN` 与 `insertRcaTask` 同口径，`task_key` 带随机后缀避开唯一键，
     * `deadline_at` 显式留空——用例要验的正是「领取时把它补上」。
     */
    private fun newPendingTask(): Long {
        val episodeId = jdbc.queryForObject(
            "SELECT id FROM t_feedback_episode ORDER BY id LIMIT 1", Long::class.java
        ) ?: error("库里没有 episode，先跑接入测试（RCA_INTAKE_ENTRY）再跑本测试")
        jdbc.update(
            """
            INSERT INTO t_rca_agent_task (public_id, task_key, episode_id, deadline_at)
            VALUES (UUID_TO_BIN(?), ?, ?, NULL)
            """.trimIndent(),
            UUID.randomUUID().toString(), "itest-lease-" + UUID.randomUUID().toString().take(12), episodeId
        )
        // 同一个事务里共用一条连接，LAST_INSERT_ID() 因此是本行而不是别人的。
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long::class.java)!!
    }

    /** 走被测语句本身，超时参数取**属性里绑定的那个值**（而不是用例常量）。 */
    private fun claim(taskId: Long, owner: String): Int =
        mapper.claim(taskId, owner, LEASE_SECONDS, properties.taskTimeoutMinutes)

    private fun expireLease(taskId: Long) {
        jdbc.update(
            "UPDATE t_rca_agent_task SET lease_until = DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id = ?",
            taskId
        )
    }

    /** 读回领取结果里那一个字段；读不回来说明语句的匹配条件与写入的不一致。 */
    private fun deadlineRemainingOf(taskId: Long, owner: String): Long? =
        (mapper.findClaimed(taskId, owner) ?: error("按 (id=$taskId, owner=$owner) 读不回任务行"))
            .deadlineRemainingMs

    private fun deadlineOf(taskId: Long): LocalDateTime = jdbc.queryForObject(
        "SELECT deadline_at FROM t_rca_agent_task WHERE id = ?", LocalDateTime::class.java, taskId
    )!!

    private fun generationOf(taskId: Long): Long = jdbc.queryForObject(
        "SELECT lease_generation FROM t_rca_agent_task WHERE id = ?", Long::class.java, taskId
    )!!

    private fun traceIdOf(taskId: Long): String? = jdbc.queryForObject(
        "SELECT trace_id FROM t_rca_agent_task WHERE id = ?", String::class.java, taskId
    )

    /** 3306 是别的项目的库；本测试会写数据，端口判错就是在写别人的库。 */
    private fun assertLocalPort() {
        assertTrue(
            datasourceUrl.contains(":3307"),
            "租约契约测试只允许连本机 feedback-platform 的 3307，当前地址端口不对"
        )
    }

    companion object {
        /** 与 `@SpringBootTest` 属性里的 `app.rca.task-timeout-minutes=11` 是同一个数。 */
        private const val TIMEOUT_MINUTES = 11L
        private const val LEASE_SECONDS = 180L
        private const val OWNER_1 = "itest-lease-owner-1"
        private const val OWNER_2 = "itest-lease-owner-2"

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
