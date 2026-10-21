package com.feedback.agent.rca.runtime

import com.feedback.agent.rca.domain.ToolCallRecord
import com.feedback.agent.rca.domain.ToolResult

/**
 * 一次**已经决定要发、结果尚未落库**的工具调用。
 *
 * 这个对象存在的唯一理由，是把「崩在工具调用中间」这段窗口变成可判定的：它先落库、
 * 再发请求，于是「请求发出去了但结果没记下来」在库里留下一条 `PENDING` 记录，
 * 而不是留下一片空白。空白与「压根没调用过」在事后**无法区分**，
 * 而这两者的处置正好相反——前者不能重放（上游可能已经执行），后者必须重放。
 *
 * [paramsSnapshotJson] 是调用参数的 JSON 快照，由实现侧序列化后落库。
 * 同一条记录里已经有 [paramsDigest]，但摘要不可逆：崩溃后要重放这次调用，
 * 光有摘要重建不出参数。**摘要用于判等，快照用于重建**，两者都要有，
 * 不能因为「已经有摘要了」就把快照省掉。
 */
data class PendingToolCall(
    val taskId: Long,
    val toolName: String,
    val toolVersion: String,
    val decisionRound: Int,
    val paramsDigest: String,
    val params: Map<String, Any?>,
    /**
     * 本次任务执行的链路标识，与 `t_rca_agent_task.trace_id` 同值。
     *
     * 为 null 时 [spanId] 必须也为 null：span 是从 trace 推出来的，没有 trace 就没有 span
     * （数据库侧 `chk_rca_tool_trace_span_pair` 是同一条不变式）。两列一起为空的含义是
     * 「这次执行没有链路标识」，由调用方显式给出，写入侧不做兜底生成——
     * 编一个标识出来会让「哪些调用真的带链路」这个问题永远无法回答。
     */
    val traceId: String? = null,
    /** 本次调用的 span，由 trace 与调用四要素确定性推导（见 `ToolSpanIdFactory`）。 */
    val spanId: String? = null
)

/**
 * `t_rca_tool_call` 的**写入侧**。
 *
 * ## 为什么必须与读侧分开成两个端口
 *
 * 读侧 [RcaToolCallJournalPort] 是 `fun interface`，它的值形态被用在大量「只要一个函数」
 * 的位置（[RcaRecoveryResolver.NONE] 就是其一）。给它加方法会把这些构造点全部改掉，
 * 而读侧真正需要的语义只有「按任务取记录」一件。所以拆成两个：编排层读它，工具边界写它。
 * 两个端口由**同一个适配器**实现，依赖方向相反，端口本身不需要知道对方存在。
 *
 * ## 两阶段写入，以及它为什么必须在守卫里做
 *
 * [begin] 在**发起上游调用之前**落一条 `PENDING` 行，[commit] 在拿到结果之后把它推到
 * `COMMITTED`。中间那段就是崩溃窗口。这样切分带来三条**从记录形状推出来的**性质，
 * 不依赖调用方记得做对什么：
 *
 * - 崩在 [begin] 之前 → 库里没有这一行，等价于「没调用过」。正确：请求确实没发出去。
 * - 崩在 [begin] 与 [commit] 之间 → 留下 `PENDING` 且上游请求 ID 为空，
 *   恢复判定落到 `REPLAY_READ_ONLY`。只读工具重放是安全的，且首次调用从未提交，
 *   因此不产生重复证据、也不产生重复扣费。
 * - 崩在 [commit] 之后 → `COMMITTED`，恢复时按账本复用。
 *
 * 写入点只能是 [com.feedback.agent.rca.tools.ToolInvocationGuard]：它是全部工具调用的唯一
 * 出口，在别处补写必然漏掉某条路径（模型发起的调用与基线取证走的是同一个出口，
 * 但那两处的调用方式不同，分别写就是两份可能漂移的实现）。
 *
 * ## 复用与拒绝时**不写**
 *
 * 守卫命中历史 Observation、或预算拒绝时都不会调用本端口。这与「复用不产生新证据」
 * 是同一条规则：为复用补一条记录会让调用次数虚高，而调用次数是预算与审计的输入，
 * 虚高会让「这次调查花了多少步」这个问题得到错误答案。
 *
 * ## [NONE] 的语义
 *
 * [NONE] 表示「这套部署没有调用记录表」：它什么都不写，语义是**这一层没有持久化**，
 * 不是「已经记好了」。离线回放的三个 `main()` 用它。接了记录表的部署必须注入真实实现，
 * 否则 [com.feedback.agent.rca.runtime.RcaWorker] 的恢复判定永远看不到在途调用。
 */
interface RcaToolCallJournalWriter {

    /**
     * 落一条未落定的调用记录，返回行主键。
     *
     * 同一 `(任务, 工具, 参数摘要, 轮次)` 若已存在**未落定**的行，必须复用该行而不是新插一条。
     * 理由是崩溃恢复会重跑同一个节点：新插一条会让同一次取证在库里出现两次，
     * 恢复判定于是看到两条 `PENDING` 并各自重放一次。
     */
    fun begin(call: PendingToolCall): Long

    /**
     * 回写这次调用的结果，把记录从 `PENDING` 推到 `COMMITTED`。
     *
     * 只允许改**仍是 `PENDING`** 的行。一条已提交的记录若被重放路径再写一次，
     * 审计会看到「同一次调用提交了两遍」，而真正发生过的事情只有一遍。
     */
    fun commit(rowId: Long, record: ToolCallRecord, result: ToolResult)

    companion object {

        /** 没有落库时返回的行主键；调用方据此把内存记录的主键留空。 */
        const val NO_ROW: Long = 0

        val NONE: RcaToolCallJournalWriter = object : RcaToolCallJournalWriter {

            override fun begin(call: PendingToolCall): Long = NO_ROW

            override fun commit(rowId: Long, record: ToolCallRecord, result: ToolResult) = Unit
        }
    }
}
