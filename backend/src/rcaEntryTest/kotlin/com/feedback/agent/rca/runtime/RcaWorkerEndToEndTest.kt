package com.feedback.agent.rca.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * 执行入口的端到端验证：待执行的 `t_rca_agent_task` → 终态 + 状态版本递增 + 租约释放。
 *
 * ## 这个测试**刻意不调用真模型**
 *
 * `@SpringBootTest` 属性里把 `app.ai.llm.api-key` 与 `app.ai.llm.model` **显式清空**，
 * 于是 `OpenAiCompatibleLlmClient.requireConfigured()` 抛 `LLM_NOT_CONFIGURED`，
 * `LlmInvestigationModel` 把它转成一条 `HUMAN_REQUIRED` 停止提案。
 *
 * 这是用户已确认的口径：**只验证降级路径，不真调 LLM**。若不显式清空，
 * 本机 `~/.config/feedback-platform/openai.env` 里的密钥会让这个测试真的发起网络调用——
 * 一个会花钱、会被地区/配额影响、且结果不确定的测试。清空是使测试**确定性**的手段，
 * 不是漏配。
 *
 * ## 断言的是「每个被领取的任务都收口了」，不是「结论是什么」
 *
 * 五个上游端口在本仓库都没有数据落点（见 `RcaUpstreamUnavailableRepository`），
 * 所以**结论多为转人工**。这正是「证据不足不下结论」被真实触发，是预期行为而不是失败，
 * 因此本类不断言具体终态，只断言终态集合的合法性与收口性，并把实测分布**打印出来**。
 * 把分布断言成某个具体值，等于把「当前上游接入程度」写进测试——
 * 接上一个数据源就会让测试变红，而那时变红的恰恰是正确的结果。
 *
 * ## 领取数由配置固定
 *
 * `app.rca.worker-max-tasks-per-run=5`：断言「恰好 5 个任务被领取」需要一个已知的上限。
 * 743 个任务全跑一遍对验证入口没有任何额外信息量，只增加耗时与写入量。
 */
@EnabledIfEnvironmentVariable(named = "RCA_ENTRY_TEST_ENABLED", matches = "true")
@SpringBootTest(
    properties = [
        "xxl.job.enabled=false",
        "spring.flyway.enabled=false",
        "spring.main.lazy-initialization=true",
        "app.rca.intake-enabled=false",
        "app.rca.worker-enabled=true",
        "app.rca.worker-id=rca-entry-test-worker",
        "app.rca.lease-seconds=180",
        "app.rca.worker-max-tasks-per-run=5",
        // 刻意取一个既不是默认值 30、也不是另一个测试用的 11 的数：
        // 领取语句若把分钟写死，下面的断言就会看到 30 而不是 17。
        "app.rca.task-timeout-minutes=17",
        // 显式清空：让模型走 LLM_NOT_CONFIGURED 降级出口，绝不发起真实调用。
        "app.ai.llm.api-key=",
        "app.ai.llm.model="
    ]
)
class RcaWorkerEndToEndTest @Autowired constructor(
    private val scheduler: RcaWorkerScheduler,
    private val jdbc: JdbcTemplate,
    @Value("\${spring.datasource.url}") private val datasourceUrl: String
) {

    /** 由 `chk_rca_agent_task_status` 定义，是本平台全部合法终态。 */
    private val terminalStatuses = listOf("COMPLETED", "PARTIAL", "NEEDS_HUMAN", "FAILED", "CANCELLED")

    @Test
    fun `worker 把待执行任务跑到终态并落库，且不留下租约`() {
        assertTrue(
            datasourceUrl.contains(":3307"),
            "执行测试只允许连本机 feedback-platform 的 3307，当前地址端口不对"
        )

        val pendingBefore = jdbc.queryForList(
            "SELECT id FROM t_rca_agent_task WHERE task_status = 'PENDING' AND lease_owner IS NULL ORDER BY id",
            Long::class.java
        )
        assertTrue(
            pendingBefore.size >= 5,
            "待执行任务不足 5 个（实际 ${pendingBefore.size}），先跑接入测试再跑本测试"
        )

        // 本类必须可重复运行，所以断言的是**本次新领取**的集合，不是「所有被领取过的任务」。
        // 直接用 `attempt_count >= 1` 做全量断言会让第二次运行看到 10 个而失败——
        // 那是上一次运行留下的痕迹，不是本次的行为。
        val claimedBefore = jdbc.queryForList(
            "SELECT id FROM t_rca_agent_task WHERE attempt_count >= 1 ORDER BY id",
            Long::class.java
        ).toSet()

        val expected = 5
        scheduler.runPending()

        // 领取由 `attempt_count` 递增留痕，不依赖返回值。
        val newlyClaimed = jdbc.queryForList(
            "SELECT id FROM t_rca_agent_task WHERE attempt_count >= 1 ORDER BY id",
            Long::class.java
        ).filterNot(claimedBefore::contains)
        println("RCA_WORKER_ENTRY claimedBefore=${claimedBefore.size} newlyClaimed=$newlyClaimed")

        // 领取集合必须是待执行任务的**前缀**（按 id 升序领取），且恰好 5 个。
        // 「前缀」这一条同时验证了两件事：领取是有序的，且没有跳过候选。
        assertEquals(expected, newlyClaimed.size, "本次领取的任务数不等于 worker-max-tasks-per-run")
        assertEquals(
            pendingBefore.take(expected), newlyClaimed,
            "被领取的任务不是 id 升序的前 5 个待执行任务：领取顺序或候选条件与预期不符"
        )

        // 租约与状态版本：领取必须递增 state_version（fencing 靠它），
        // 且每个任务至少被领取过一次。
        val notLeased = jdbc.queryForList(
            "SELECT id FROM t_rca_agent_task WHERE attempt_count >= 1 AND lease_owner IS NOT NULL AND lease_until > NOW(3)",
            Long::class.java
        )
        val minStateVersion = jdbc.queryForObject(
            "SELECT MIN(state_version) FROM t_rca_agent_task WHERE attempt_count >= 1",
            Long::class.java
        )!!
        assertTrue(minStateVersion >= 1, "领取没有递增 state_version：fencing 会失效")

        // 领取必须**补上截止时间**，而且补的是配置里那个数。
        // 这一列在本次改动之前恒为 NULL（insertRcaTask 只写四个列），于是候选条件里的
        // (deadline_at IS NULL OR deadline_at > NOW(3)) 恒真，task-timeout-minutes 没有落点。
        // 用「剩余秒数」而不是「列非空」来断言，是为了同时盯住单位与取值来源。
        val deadlineSeconds = jdbc.query(
            """
            SELECT id, TIMESTAMPDIFF(SECOND, NOW(3), deadline_at) AS until_deadline
            FROM t_rca_agent_task WHERE attempt_count >= 1
            """.trimIndent()
        ) { rs, _ -> rs.getLong("id") to rs.getObject("until_deadline") as Number? }
            .filter { (id, _) -> newlyClaimed.contains(id) }

        assertEquals(expected, deadlineSeconds.size, "本次领取的任务里有的没查到截止时间")
        deadlineSeconds.forEach { (id, until) ->
            assertNotNull(until, "任务 $id 领取后 deadline_at 仍为 NULL：这一列还是没有写入侧")
            assertTrue(
                until!!.toLong() in (EXPECTED_TIMEOUT_MINUTES * 60 - 5)..(EXPECTED_TIMEOUT_MINUTES * 60),
                "任务 $id 的截止时间不是「领取时刻 + $EXPECTED_TIMEOUT_MINUTES 分钟」，实际剩余 $until 秒" +
                    "（为 30 分钟说明领取语句没读配置，为 0 说明单位或起算点写错了）"
            )
        }

        // 终态行必须带 stop_reason。`chk_rca_agent_terminal` 在库层面强制这一点，
        // 这里再核一遍是为了让「约束真的生效」有测试证据，而不是只有一条 DDL。
        val terminalWithoutReason = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM t_rca_agent_task
            WHERE task_status IN ('COMPLETED','PARTIAL','NEEDS_HUMAN','FAILED','CANCELLED')
              AND stop_reason IS NULL
            """.trimIndent(),
            Long::class.java
        )!!
        assertEquals(0L, terminalWithoutReason, "存在没有 stop_reason 的终态任务")

        val histogram = jdbc.query(
            """
            SELECT task_status, stop_reason, COUNT(*) n FROM t_rca_agent_task
            WHERE attempt_count >= 1 GROUP BY task_status, stop_reason ORDER BY n DESC
            """.trimIndent()
        ) { rs, _ ->
            Triple(rs.getString("task_status"), rs.getString("stop_reason"), rs.getLong("n"))
        }
        println("RCA_WORKER_ENTRY claimedOutcome=$histogram notLeased=$notLeased")

        // 每个被领取的任务都必须收口成合法终态。允许存在尚未终态的任务
        // （图可能停在一个需要再次领取的边界上），但那种情况必须还持有租约——
        // 否则就是一个既没跑完、又没人负责的任务，那才是真正的泄漏。
        val claimedRows = jdbc.query(
            "SELECT id, task_status, stop_reason, lease_owner FROM t_rca_agent_task WHERE attempt_count >= 1 ORDER BY id"
        ) { rs, _ ->
            listOf(
                rs.getLong("id"), rs.getString("task_status"),
                rs.getString("stop_reason"), rs.getString("lease_owner")
            )
        }
        claimedRows.forEach { row ->
            val id = row[0] as Long
            val status = row[1] as String
            val stopReason = row[2] as String?
            val leaseOwner = row[3] as String?
            if (status in terminalStatuses) {
                assertNotNull(stopReason, "任务 $id 已终态（$status）却没有 stop_reason")
                assertEquals(null, leaseOwner, "任务 $id 已终态（$status）却仍持有租约 $leaseOwner")
            } else {
                assertTrue(
                    status == "RUNNING" || status == "RETRY_PENDING",
                    "任务 $id 处于非法中间态 $status（chk_rca_agent_task_status 应已拒绝）"
                )
                assertNotNull(leaseOwner, "任务 $id 既未终态（$status）又不持有租约：无人负责的泄漏")
            }
        }

        // 本次断言里最重要的一条：**终态任务必须释放租约**。
        // 未释放会让任务在租约到期前既不可再领、也没有执行者。
        val terminalLeaked = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM t_rca_agent_task
            WHERE task_status IN ('COMPLETED','PARTIAL','NEEDS_HUMAN','FAILED','CANCELLED')
              AND lease_owner IS NOT NULL
            """.trimIndent(),
            Long::class.java
        )!!
        assertEquals(0L, terminalLeaked, "终态任务仍持有租约")
    }

    companion object {
        /** 与 `@SpringBootTest` 属性里的 `app.rca.task-timeout-minutes=17` 是同一个数。 */
        private const val EXPECTED_TIMEOUT_MINUTES = 17L

        /**
         * 只补**测试专用**的固定密钥，不补数据库地址（理由同 `RcaIntakeEndToEndTest`）。
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
