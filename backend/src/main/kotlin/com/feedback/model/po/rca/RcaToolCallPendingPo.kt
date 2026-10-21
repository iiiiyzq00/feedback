package com.feedback.model.po.rca

/**
 * `t_rca_tool_call` 的**发起**行：这次调用已经决定要发，但上游结果还没有回来。
 *
 * 三个常量由语句固定而不是开放给调用方，每一条都对应一个不能含糊的语义：
 *
 * - `local_commit_state` 固定 `PENDING`：这条语句的**全部意义**就是「本地还没落定」。
 *   把它做成参数，就等于允许调用方在发起阶段就宣称已提交，两阶段写入随即失效。
 * - `result_status` 固定 `UNKNOWN`：此刻确实还不知道结果，而 `UNKNOWN` 的语义正是
 *   「未归类失败，调用方必须显式处理」。这里还有一层更硬的原因——
 *   `chk_rca_tool_result_status` 的取值集合里**没有** `PENDING`，
 *   想写一个「进行中」的状态也写不进去，只能诚实地说「未知」。
 *   `RcaToolRecovery.decidePending` 不读 `status`，所以这两个字段不冲突。
 * - `provider_request_id` 不写（留 NULL）：上游请求 ID 由结果带回。
 *   在发起阶段编一个出来，会让恢复判定把「无从对账」误判成「可以按 ID 对账」，
 *   进而去问上游一个不存在的单据。
 *
 * [paramsSnapshotJson] 是调用参数的 JSON 快照，由调用方拼好传入，
 * SQL 里不做任何字符串拼接（本仓库的 `MyBatisBindingGateTest` 禁止未白名单的 `${}`）。
 *
 * ## 两列链路标识为什么在这里，而不在 `ToolCallRecord` 上
 *
 * [traceId] / [spanId] 只在**发起**这一步落到这一行上，**不进** `ToolCallRecord`、
 * 也就不进共享状态。理由是链路标识属于调度信息：`ToolCallRecord` 会被序列化进任务快照，
 * 让快照的内容依赖「这一次是哪个调度器领的」会把固定回放（快照逐字节相等）变成
 * 一件取决于调度的事。两列的来源与推导见 `ToolInvocationGuard` 与 `ToolSpanIdFactory`。
 *
 * 两列可空且必须**同时**为空或同时非空（`chk_rca_tool_trace_span_pair` 是同一条不变式）：
 * 为 null 表示这次执行没有链路标识（离线夹具，或早于该列引入的历史行）。
 * 写入侧不做兜底生成——编一个出来会让「哪些调用真的带链路」永远无法回答。
 */
data class RcaToolCallPendingPo(
    /** 由 `useGeneratedKeys` 回填，构造时无需给值。 */
    var id: Long = 0,
    val taskId: Long,
    val toolName: String,
    val toolVersion: String,
    val decisionRound: Int,
    val paramsDigest: String,
    val paramsSnapshotJson: String?,
    /** 冗余自 `t_rca_agent_task.trace_id`，使单行调用记录可以脱离任务表被引用。 */
    val traceId: String? = null,
    /** 由 trace 与调用四要素确定性推导（见 `ToolSpanIdFactory`）。 */
    val spanId: String? = null
)
