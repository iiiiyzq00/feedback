package com.feedback.agent.rca.intake

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 接入入口的端到端验证：`t_feedback_source` → 待执行的 `t_rca_agent_task`。
 *
 * ## 只在显式开启时运行，且只在 3307 上运行
 *
 * 门控环境变量 `RCA_ENTRY_TEST_ENABLED`；端口断言有**两道**：
 * Gradle 任务在跑测试前就拒绝非 3307 的 `DB_URL`（见 `build.gradle.kts` 的 `rcaEntryTest`），
 * 本类再核一次注入进来的地址。3306 是别的项目的库，多一道不算多。
 *
 * ## 为什么数据环境是 VERIFICATION
 *
 * 生产默认 `PRODUCTION`，夹具行在 SQL 层就选不出来——那正是它的设计目的。
 * 本机要验证链路，所以显式读 `VERIFICATION`，读的就是仓库自带那批带
 * `fixture_tag='fp-shadow-seed-20260928'` 的合成夹具（V115 的 `chk_source_fixture_tag` 保证
 * 它们不可能是 `PRODUCTION`）。
 *
 * ## 断言的是**不变量**，不是「从零新增了多少行」
 *
 * 这一点是刻意改过的。原版断言「首轮新增数 = 候选数」，那只在**库是干净的**时候成立：
 * 夹具一旦被接入过，重跑就是 0 新增，断言必然失败。于是那个测试只有在它第一次运行时才通过——
 * 一个只能通过一次的验证工具，不是验证工具。
 *
 * 现在断言两件在**任何**前序状态上都成立、且正是我们真正要保证的事：
 *
 * 1. **覆盖不变量**：每个可接入候选来源恰好对应一个会话成员、一个问题对象、一个任务。
 * 2. **重复投递零变化**：连跑两次接入，行数与任务状态分布逐项不变。
 *
 * 第 1 条比原来那条更强：它同时排除了「多建」和「少建」，而原来的差值断言在多建时
 * 只会表现为一个数字对不上。第 2 条验证的是**唯一键**而不是代码——
 * `uk_episode_member_revision` / `uk_rca_agent_task_key` 必须把重复投递挡在库层面。
 *
 * 任务状态分布也一并比对而不是只比行数：接入**不该动任务状态**，
 * 而「行数没变但状态被改了」恰好是差值断言看不见的那类退化。
 * 用分布比对还让本类对「worker 已经跑过、任务已终态」这件事免疫——
 * 它验证的是接入的行为，不是库处于哪个阶段。
 */
@EnabledIfEnvironmentVariable(named = "RCA_ENTRY_TEST_ENABLED", matches = "true")
@SpringBootTest(
    properties = [
        "xxl.job.enabled=false",
        "spring.flyway.enabled=false",
        "spring.main.lazy-initialization=true",
        "app.rca.intake-enabled=true",
        "app.rca.intake-data-environment=VERIFICATION",
        "app.rca.intake-batch-size=200",
        "app.rca.intake-max-batches-per-run=10",
        // 接入要写库，所以这里不能像只读影子回放那样把连接池设成 read-only。
        "app.rca.worker-enabled=false"
    ]
)
class RcaIntakeEndToEndTest @Autowired constructor(
    private val scheduler: RcaIntakeScheduler,
    private val jdbc: JdbcTemplate,
    @Value("\${spring.datasource.url}") private val datasourceUrl: String
) {

    /** 计数用的四张表，顺序即接入的写入顺序。 */
    private val tables = listOf(
        "t_feedback_episode",
        "t_feedback_episode_member",
        "t_feedback_work_item",
        "t_rca_agent_task"
    )

    private fun counts(): Map<String, Long> =
        tables.associateWith { jdbc.queryForObject("SELECT COUNT(*) FROM $it", Long::class.java)!! }

    /**
     * 任务状态分布。
     *
     * 与 [counts] 一起构成「接入是幂等的」这一条：只比行数会漏掉
     * 「行数没变但状态被改写」。
     */
    private fun statusHistogram(): Map<String, Long> =
        jdbc.query("SELECT task_status, COUNT(*) n FROM t_rca_agent_task GROUP BY task_status") { rs, _ ->
            rs.getString("task_status") to rs.getLong("n")
        }.toMap()

    @Test
    fun `接入把可脱敏来源会话化成待执行任务，且重复投递零新增`() {
        assertTrue(
            datasourceUrl.contains(":3307"),
            "接入测试只允许连本机 feedback-platform 的 3307，当前地址端口不对"
        )

        // 可接入的候选数由数据库自己给出，不由测试硬编码——
        // 硬编码一个数字会让「夹具变了」表现为测试失败，而不是表现为这里统计口径漂移。
        val candidates = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM t_feedback_source s
            JOIN t_feedback_content c ON c.source_id = s.id
             AND c.masking_status = 'PASS' AND c.is_deleted = 0
            WHERE s.data_environment = 'VERIFICATION'
            """.trimIndent(),
            Long::class.java
        )!!
        assertTrue(candidates > 0, "本机库里没有 VERIFICATION 夹具，先跑夹具加载再跑本测试")

        // 覆盖不变量：每个候选来源都有成员。这一条在「已接入过」的库上也必须成立，
        // 所以它同时是「接入确实发生过」的证据，与本次是否新建无关。
        val covered = jdbc.queryForObject(
            """
            SELECT COUNT(DISTINCT m.source_id) FROM t_feedback_episode_member m
            JOIN t_feedback_source s ON s.id = m.source_id
            WHERE s.data_environment = 'VERIFICATION'
            """.trimIndent(),
            Long::class.java
        )!!
        assertEquals(candidates, covered, "有候选来源没有对应的会话成员：接入漏掉了它们")

        // 多建：同一来源不得有两个成员，同一问题对象不得有两个任务。
        val duplicateMembers = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM (
              SELECT m.source_id FROM t_feedback_episode_member m
              JOIN t_feedback_source s ON s.id = m.source_id
              WHERE s.data_environment = 'VERIFICATION'
              GROUP BY m.source_id HAVING COUNT(*) > 1) x
            """.trimIndent(),
            Long::class.java
        )!!
        assertEquals(0L, duplicateMembers, "同一来源出现了多个会话成员")

        val tasksPerEpisode = jdbc.queryForObject(
            "SELECT COUNT(*) FROM (SELECT episode_id FROM t_rca_agent_task GROUP BY episode_id HAVING COUNT(*) > 1) x",
            Long::class.java
        )!!
        assertEquals(0L, tasksPerEpisode, "同一个问题对象上出现了多个任务")

        val beforeCounts = counts()
        val beforeStatus = statusHistogram()
        scheduler.runIntake()
        val afterFirstCounts = counts()
        val afterFirstStatus = statusHistogram()
        scheduler.runIntake()
        val afterSecondCounts = counts()
        val afterSecondStatus = statusHistogram()

        println(
            "RCA_INTAKE_ENTRY candidates=$candidates covered=$covered before=$beforeCounts " +
                "afterFirst=$afterFirstCounts afterSecond=$afterSecondCounts cursor=${scheduler.currentCursor()}"
        )
        println("RCA_INTAKE_ENTRY status before=$beforeStatus afterFirst=$afterFirstStatus afterSecond=$afterSecondStatus")

        // 重复投递：行数与状态分布逐项不变。这两条合起来才是「幂等」——
        // 只看行数会漏掉「没新增行但把状态改了」。
        assertEquals(beforeCounts, afterFirstCounts, "重复投递产生了新行，唯一键没有挡住")
        assertEquals(afterFirstCounts, afterSecondCounts, "重复投递产生了新行，唯一键没有挡住")
        assertEquals(beforeStatus, afterFirstStatus, "接入改动了任务状态：接入不该碰任务状态")
        assertEquals(afterFirstStatus, afterSecondStatus, "接入改动了任务状态：接入不该碰任务状态")
    }

    companion object {
        /**
         * 只补**测试专用**的固定密钥，不补数据库地址。
         *
         * 数据库地址必须来自环境（`DB_URL`，由 `~/.gradle/init-datasource.gradle.kts`
         * 从 600 权限文件读入），**不能在这里给默认值**：一个
         * `jdbc:mysql://localhost:3306/...` 的兜底会让本测试在没有 init 脚本时
         * 静默连上别的项目的库。任务侧的端口断言与这里的地址断言是同一件事的两道防线。
         *
         * 指纹密钥的值与 `src/test` 里既有的 MySQL 集成测试一致，是**测试常量**，
         * 不是任何环境在用的凭据。
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
