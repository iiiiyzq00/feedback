package com.feedback.agent.rca.persistence

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * `V243__rca_trace_correlation.sql` 的文本契约。
 *
 * ## 为什么这一条值得有个测试（而不是「迁移文件写了就完了」）
 *
 * 本迁移里有五条**只有靠约束才守得住**的性质，都是「少写一句就静默失效」的形状：
 *
 * 1. 两列都必须是 `CHAR` 而不是 `VARCHAR`：宽度是格式的一部分（W3C Trace Context 的
 *    32/16 位），变长列会让「长度对不对」这件事重新回到人手里。
 * 2. 格式约束必须在**库上**（`CHECK ... REGEXP`），不只在写入侧。写入侧只有一处实现
 *    没错，但将来接入第二条写入路径（比如链路追踪后端回填）时，少了库上的约束
 *    就再也没有第二道防线。
 * 3. **两列同进同出**（`chk_rca_tool_trace_span_pair`）。只校验各自的格式会放过
 *    「有 span 没 trace」——span 是从 trace 推出来的，那种行无法解释。
 * 4. **不回填历史行**。文件里一旦出现 `UPDATE ... SET trace_id =`，
 *    743 条夹具任务与它们此前的调用记录就会被编上一个不存在的执行标识，
 *    「这一行早于该标识的引入」这个可解释的事实随即消失。
 * 5. **不加到 `t_rca_agent_step` 上**。那张表今天没有任何写入侧，
 *    加过去的列不会有写入方也不会有读取方。
 *
 * 这些性质在 `:backend:rcaEntryTest` 里验不了——那个套件验的是「写入侧真的写进去了」，
 * 而「约束真的在库上」只有往库里插一条非法值才会暴露，那需要另一条独立的用例。
 * 本测试盯住的是迁移文件的**语句**：它是这两件事的共同来源。
 *
 * ## 只断言语句，不断言注释
 *
 * 断言跑在 [ddl] 上（剥掉 `--` 注释之后的正文）。不这么做的话，注释里
 * 「可空而不是 NOT NULL DEFAULT」「不加在 t_rca_agent_step 上」这些**解释性**措辞
 * 会把对应的 `assertFalse` 全部打成失败——而它们恰恰是在说明为什么不那么做。
 * 测试要守的是「文件里没有这条语句」，不是「文件里没提过这几个字」。
 *
 * 与仓库既有写法一致（`ReportIdempotencyMigrationTest` 等）：读一个文件，断言其中出现
 * 关键片段。刻意**不**断言整段 SQL——那会让任何排版调整都变成一次测试失败。
 */
class RcaTraceCorrelationMigrationTest {

    private val file: String =
        Files.readString(Path.of("src/main/resources/db/migration/V243__rca_trace_correlation.sql"))

    /** 迁移正文：去掉 `--` 行注释，只留下会被数据库执行的部分。 */
    private val ddl: String = file.lineSequence()
        .map { line -> line.substringBefore("--") }
        .joinToString("\n")

    @Test
    fun `两列都是定长且带库上格式约束`() {
        // 定长（CHAR 而不是 VARCHAR）：宽度是格式的一部分，不允许变长。
        assertTrue(
            ddl.contains("trace_id CHAR(32)"),
            "t_rca_agent_task.trace_id 不是 CHAR(32)：宽度变成可变的之后，长度对不对又回到了人手里"
        )
        assertTrue(ddl.contains("span_id CHAR(16)"), "span_id 的宽度必须与 W3C 的 span-id 一致")

        // 约束在库上，而不是只在写入侧。
        assertTrue(ddl.contains("chk_rca_agent_trace"), "任务表的 trace 格式约束缺失")
        assertTrue(ddl.contains("chk_rca_tool_trace"), "调用表的 trace 格式约束缺失")
        assertTrue(ddl.contains("chk_rca_tool_span"), "调用表的 span 格式约束缺失")
        // 具体格式：32 位与 16 位**小写**十六进制。大写混进来会让同一个标识在两张表上
        // 长得不一样，join 时对不上。
        assertTrue(ddl.contains("REGEXP '^[0-9a-f]{32}\$'"), "trace 的格式不是 32 位小写十六进制")
        assertTrue(ddl.contains("REGEXP '^[0-9a-f]{16}\$'"), "span 的格式不是 16 位小写十六进制")
    }

    @Test
    fun `两列必须同进同出，不允许只有 span 没有 trace`() {
        assertTrue(
            ddl.contains("chk_rca_tool_trace_span_pair") &&
                ddl.contains("(trace_id IS NULL) = (span_id IS NULL)"),
            "缺少「trace 与 span 同时有值或同时为空」的约束：那样「有 span 没 trace」的行可以被写进去，" +
                "而 span 是从 trace 推出来的，这种行无法解释"
        )
    }

    @Test
    fun `历史行不被回填，NULL 是被允许的取值`() {
        assertTrue(
            ddl.contains("DEFAULT NULL"),
            "两列必须可空：历史行与「本次执行没有链路标识」这两种情况都靠 NULL 表达"
        )
        assertFalse(
            ddl.uppercase().contains("NOT NULL"),
            "链路列被设成了 NOT NULL：历史行与离线夹具就会被强迫也必须有一个值，" +
                "而它们确实没有——那只能靠编一个来满足，正是本迁移要防的"
        )
        // 回填会伪造历史：那 743 条夹具任务发生在链路标识存在之前，
        // 给它们编一个 trace_id 等于宣称它们曾被追踪过。
        val upper = ddl.uppercase()
        assertFalse(
            upper.contains("UPDATE T_RCA_AGENT_TASK") || upper.contains("SET TRACE_ID"),
            "迁移里出现了回填语句：历史行会被编上一个从未存在过的执行标识"
        )
    }

    @Test
    fun `步骤表刻意不加链路列`() {
        // t_rca_agent_step 今天没有任何写入侧。往里加列会得到一组
        // 「没有写入方、也没有读取方」的列——RcaBudgetPolicy.policyVersion 已经吃过一次这个亏。
        assertFalse(
            ddl.contains("t_rca_agent_step"),
            "本迁移动了 t_rca_agent_step：那张表没有写入侧，加过去的列不会有任何人写或读"
        )
    }

    @Test
    fun `按链路取调用记录有索引，且顺序是 trace 在前`() {
        // 排障的第一动作是「按一次任务执行捞出全部工具调用」，查询条件就是 trace_id。
        // 列序写成 (span_id, trace_id) 会让这个查询用不上前导列。
        assertTrue(
            ddl.contains("KEY idx_rca_tool_trace (trace_id, span_id)"),
            "缺少按 trace_id 前导的索引：排障时按链路捞调用记录会退化成全表扫描"
        )
    }
}
