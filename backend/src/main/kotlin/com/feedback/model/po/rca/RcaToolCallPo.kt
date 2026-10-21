package com.feedback.model.po.rca

import java.math.BigDecimal

/**
 * `t_rca_tool_call` 的行视图，供恢复判定读取。
 *
 * 字段与 `ToolCallRecord` 一一对应，**刻意不含** `params_snapshot` /
 * `observation_snapshot` 两个 JSON 列：恢复判定要看的是「这次调用走到了哪一步」，
 * 不是参数与观察的正文。正文按证据账本回源（`evidence_id` 指向账本条目），
 * 把它们读进来只会让每次领取任务都多搬一批无用的 JSON。
 *
 * [observationSummary] 例外，它从 `observation_snapshot` 的 `$.summary` 里抽出来：
 * 守卫的去重条件要求「摘要正文非空」，而这**必须**能跨进程恢复——
 * 不填会让重启后的去重判空落空，于是恢复层刚判完「复用」，
 * 节点转头又把同样的调用发了一遍。
 *
 * 字段用 `var` 且带默认值：MyBatis 按列名注入（`map-underscore-to-camel-case` 已开启），
 * 与 [RcaAgentTaskPo] 同一写法。
 */
data class RcaToolCallPo(
    var id: Long = 0,
    var toolName: String = "",
    var toolVersion: String = "",
    var paramsDigest: String = "",
    var decisionRound: Int = 0,
    var resultStatus: String? = null,
    var providerRequestId: String? = null,
    var localCommitState: String? = null,
    var observationDigest: String? = null,
    var observationSummary: String? = null,
    var evidenceId: String? = null,
    var replayedFromId: Long? = null,
    var latencyMs: Int? = null,
    var inputTokens: Int? = null,
    var outputTokens: Int? = null,
    var costUsd: BigDecimal? = null
)
