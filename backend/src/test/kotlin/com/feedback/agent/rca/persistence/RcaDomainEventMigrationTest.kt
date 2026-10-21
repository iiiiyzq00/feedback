package com.feedback.agent.rca.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * `V244__rca_domain_event_journal.sql` 的文本契约。
 *
 * ## 这一条为什么需要一个测试
 *
 * 本表里有六条**只有靠约束才守得住**的性质，每一条的失效方式都是「少写一句，一切照常运行，
 * 直到某天有人拿它去对账」：
 *
 * 1. **`event_id` 必须定长且带库上格式约束**。定长是因为宽度是格式的一部分
 *    （与 W3C trace-id 同宽），格式约束必须在库上——写入侧今天只有一处实现，
 *    但这一列是给 MQ 订阅方当幂等键用的，将来一定会有第二条写入路径。
 * 2. **唯一键只能加在 `event_id` 上**。加一个
 *    `(aggregate_type, aggregate_id, aggregate_version)` 唯一键看起来更"严"，
 *    实际会否定设计本身：`TASK_CLAIMED` 不递增版本，所以「同一版本两条事件」
 *    是正常场景，那个键会让第一个这样的场景以插入失败告终。
 * 3. **`trace_id` 可空、且不为空时格式受约束**。历史行与「本次执行没有链路标识」
 *    都靠 NULL 表达，不许回填。
 * 4. **投递列（`published_at` / `publish_attempts`）存在且可空/有默认值**：
 *    它们属于传输，领域侧永远不写。列不在这里的话，「哪条事件还没发出去」
 *    就得另开一张表判定，而那必然引入「事件已提交、发送标记未提交」的窗口。
 * 5. **没有任何回填或改写语句**。事件日志一旦写入就不该被改写——
 *    能被改写的记不起到证据的作用。
 * 6. **外键随任务级联删除**，与 `t_rca_tool_call` 同一条约定：清理只有这一条路，
 *    没有软删列。
 *
 * ## 只断言语句，不断言注释
 *
 * 断言跑在 [ddl] 上（剥掉 `--` 注释之后的正文）。本文件的注释里**刻意**写了
 * 「不加 (aggregate_type, aggregate_id, aggregate_version) 唯一键」「没有 is_deleted」
 * 这类解释性措辞，不剥注释的话它们会把对应的 `assertFalse` 全部打成失败。
 * 测试要守的是「文件里没有这条语句」，不是「文件里没提过这几个字」。
 */
class RcaDomainEventMigrationTest {

    private val file: String =
        Files.readString(Path.of("src/main/resources/db/migration/V244__rca_domain_event_journal.sql"))

    /** 迁移正文：去掉 `--` 行注释，只留下会被数据库执行的部分。 */
    private val ddl: String = file.lineSequence()
        .map { line -> line.substringBefore("--") }
        .joinToString("\n")

    @Test
    fun `事件标识定长且格式约束在库上`() {
        assertTrue(
            ddl.contains("event_id CHAR(32)"),
            "event_id 不是 CHAR(32)：宽度是格式的一部分，变长列会让「长度对不对」重新回到人手里"
        )
        assertTrue(ddl.contains("chk_rca_domain_event_id"), "event_id 的格式约束缺失")
        assertTrue(
            ddl.contains("REGEXP '^[0-9a-f]{32}\$'"),
            "event_id 的格式不是 32 位小写十六进制：大写混进来会让同一标识在不同表上长得不一样，join 时对不上"
        )
    }

    @Test
    fun `唯一键只在 event_id 上，不额外加聚合版本唯一键`() {
        assertTrue(
            ddl.contains("uk_rca_domain_event_id (event_id)"),
            "event_id 上没有唯一键：重放会不断长出新事件，幂等失去依据"
        )
        // 断言「全文只有一个 UNIQUE KEY」而不是断言那个元组不存在：同一个元组
        // 以非唯一索引（idx_rca_domain_event_aggregate）的形式**应该**出现，
        // 按字符串找它会把一条正确的索引判成违规。
        assertEquals(
            1,
            Regex("""UNIQUE\s+KEY""", RegexOption.IGNORE_CASE).findAll(ddl).count(),
            "唯一键不止 event_id 一个：加一个 (聚合, 版本) 唯一键看起来更严，" +
                "实际会否定设计本身——TASK_CLAIMED 不递增 state_version，" +
                "「同一版本两条事件」是正常场景，那个键会让它插入失败"
        )
    }

    @Test
    fun `聚合版本不允许为负`() {
        assertTrue(
            ddl.contains("chk_rca_domain_event_version") && ddl.contains("aggregate_version >= 0"),
            "缺少 aggregate_version 的非负约束"
        )
    }

    @Test
    fun `链路标识可空、不回填，格式受约束`() {
        assertTrue(ddl.contains("trace_id CHAR(32)"), "trace_id 的宽度必须与任务表一致")
        assertTrue(
            ddl.contains("chk_rca_domain_event_trace") && ddl.contains("trace_id IS NULL OR"),
            "trace_id 的格式约束没有放行 NULL：历史行与「本次执行没有链路标识」就只能靠编一个值来满足"
        )
        assertFalse(
            ddl.uppercase().contains("SET TRACE_ID") || ddl.uppercase().contains("UPDATE T_RCA_DOMAIN_EVENT"),
            "迁移里出现了对事件表的回填或改写语句"
        )
    }

    @Test
    fun `投递标记属于传输层，有默认值且可空`() {
        assertTrue(
            ddl.contains("published_at DATETIME(3) DEFAULT NULL"),
            "published_at 不可空：NULL 正是「尚未投递」这个状态，没有别的取值能表达它"
        )
        assertTrue(
            ddl.contains("publish_attempts INT NOT NULL DEFAULT 0"),
            "publish_attempts 缺少默认值：新事件必须是 0 次投递"
        )
    }

    @Test
    fun `事件日志不可改写：没有 UPDATE 也没有 DELETE`() {
        val upper = ddl.uppercase()
        assertFalse(upper.contains("UPDATE "), "迁移里出现了 UPDATE：事件日志一旦写入就不该被改写")
        // 找 `DELETE FROM` 而不是 `DELETE`：外键上的 ON DELETE CASCADE 是**要求**出现的，
        // 按裸词找会把它判成违规。
        assertFalse(upper.contains("DELETE FROM"), "迁移里出现了 DELETE：清理只能随任务级联，不能单独删事件")
        assertFalse(
            ddl.contains("is_deleted"),
            "加了软删列：能被抹掉的历史记不起到证据的作用"
        )
    }

    @Test
    fun `外键随任务级联删除`() {
        assertTrue(
            ddl.contains("fk_rca_domain_event_task") &&
                ddl.contains("REFERENCES t_rca_agent_task(id) ON DELETE CASCADE"),
            "缺少随任务级联的外键：任务被删之后事件会变成悬空行，" +
                "而 task_id 是唯一能把事件归到某个会话上的字段"
        )
    }

    @Test
    fun `待投递扫描有 published_at 前导的索引`() {
        // 中继每轮做的事是「取一批 published_at IS NULL」，列序写反就用不上前导列。
        assertTrue(
            ddl.contains("idx_rca_domain_event_publish (published_at, id)"),
            "缺少 (published_at, id) 索引：中继每轮都会退化成全表扫描"
        )
        // 按聚合重建历史是排障的第一动作，顺序必须是 (聚合, 版本) —— 反过来版本号用不上前导列。
        assertTrue(
            ddl.contains("idx_rca_domain_event_aggregate (aggregate_type, aggregate_id, aggregate_version, id)"),
            "缺少聚合维度索引，或列序不是 (aggregate_type, aggregate_id, aggregate_version, id)"
        )
    }
}
