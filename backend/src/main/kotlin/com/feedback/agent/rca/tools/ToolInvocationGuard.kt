package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.EvidenceEntry
import com.feedback.agent.rca.domain.LocalCommitState
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.domain.RcaStopReason
import com.feedback.agent.rca.domain.RcaToolName
import com.feedback.agent.rca.domain.ToolCallRecord
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.domain.ToolResult
import com.feedback.agent.rca.runtime.PendingToolCall
import com.feedback.agent.rca.runtime.RcaToolCallJournalWriter

/**
 * 工具调用守卫：所有工具调用的唯一入口。
 *
 * 它在模型和工具之间插入三件事，每件都对应一类真实故障：
 *
 * 1. **预算与截止时间闸门**。预算耗尽时直接拒绝调用并给出 [RcaStopReason]，
 *    而不是让调用发出去再超时——后者会把失败归因到工具，掩盖了真实原因是预算。
 * 2. **用已有 Observation 取代重复取证**。恢复场景下「这一步我是不是已经查过了」必须由
 *    程序判定，判据是 `工具 + 参数摘要 + 已有完整 Observation`，而不是模型的记忆。
 *    复用会记录 [ToolCallRecord.replayedFromId]，审计上能看出哪些结果是重放的。
 * 3. **证据账本构造**。工具结果到证据的映射集中在这里，evidence ID 由
 *    `工具 + 参数摘要` 确定性生成——同一任务重放两次必须得到同一个 ID，
 *    否则引用支持校验记录的 ID 会与账本对不上，「可回源」这条就断了。
 *
 * 4. **两阶段落库**。真实调用前后各写一次调用记录（`PENDING` → `COMMITTED`），
 *    中间那段就是「上游可能已经执行、本地还没记下结果」的窗口。
 *    只在真实调用时才写：复用与预算拒绝都不产生记录，与「复用不产生新证据」同一条规则。
 *
 * ## 为什么写入点必须在这里
 *
 * 这是全部工具调用的唯一出口——模型发起的调用与基线取证走的是同一个方法。
 * 把落库放到任何一个调用方里，就等于为每种调用方式各写一份实现，两份必然漂移。
 * 端口本身是 [RcaToolCallJournalWriter]，默认 [RcaToolCallJournalWriter.NONE]
 * （「这套部署没有记录表」），离线回放因此不受影响。
 */
class ToolInvocationGuard(
    private val registry: RcaToolRegistry,
    private val journal: RcaToolCallJournalWriter = RcaToolCallJournalWriter.NONE
) {

    /**
     * 执行一次工具调用。
     *
     * @param request 调用方构造的请求；[ToolRequest.paramsDigest] 为空时由工具补齐。
     * @param state 当前共享状态，用于预算判定与历史 Observation 查找。
     * @param traceId 本次任务执行的链路标识，来自任务行（`RcaClaimedTask.traceId`），
     *   由节点从 [com.feedback.agent.rca.graph.RcaNodeContext] 原样透传下来。
     *   为 null 时不落链路列——**不生成一个代替**，理由见 [PendingToolCall.traceId]。
     */
    fun invoke(request: ToolRequest, state: RcaSharedState, traceId: String? = null): GuardedToolOutcome {
        state.gapsAndBudget.exhaustionStopReason()?.let { reason ->
            return GuardedToolOutcome(
                result = null,
                record = null,
                evidence = null,
                replayedFromId = null,
                rejectedReason = reason,
                explanation = "预算或截止时间已耗尽（${reason.storageCode}），不发起工具调用"
            )
        }

        val digest = request.paramsDigest.ifBlank { RcaCanonicalDigest.ofParams(request.params) }
        val normalized = request.copy(paramsDigest = digest)

        findReusable(state, normalized)?.let { reusable ->
            // 复用不产生新证据：evidence ID 是确定性的，账本里那一条仍然有效，
            // 新增一条只会让证据数虚高，进而让「采纳了几条证据」这类统计失真。
            return GuardedToolOutcome(
                result = null,
                record = reusable.copy(
                    id = 0,
                    decisionRound = normalized.decisionRound,
                    replayedFromId = reusable.id
                ),
                evidence = null,
                replayedFromId = reusable.id,
                rejectedReason = null,
                explanation = "命中已有完整 Observation（工具 ${request.tool.storageCode} 参数摘要一致），" +
                    "直接复用不重复取证"
            )
        }

        val invoker = registry.require(request.tool)
        // 先落一条「已发出、未落定」的记录，再发请求。顺序不能反：
        // 反过来的话，崩在两次写之间就是一个「上游执行了但本地毫无痕迹」的窗口，
        // 而那种状态在事后无法与「压根没调用」区分开。
        val rowId = journal.begin(
            PendingToolCall(
                taskId = state.taskId,
                toolName = request.tool.storageCode,
                toolVersion = invoker.toolVersion,
                decisionRound = normalized.decisionRound,
                paramsDigest = normalized.paramsDigest,
                params = normalized.params,
                traceId = traceId,
                spanId = ToolSpanIdFactory.of(
                    traceId = traceId,
                    toolName = request.tool.storageCode,
                    paramsDigest = normalized.paramsDigest,
                    decisionRound = normalized.decisionRound
                )
            )
        )
        val result = invoker.invoke(normalized)
        val record = result.toRecord(normalized)
        journal.commit(rowId, record, result)
        return GuardedToolOutcome(
            result = result,
            // 回填落库主键：内存里的这条记录随后会进 `state.toolHistory`，
            // 将来被复用时会把它写成 `replayedFromId`。留 0 会让审计指向一行不存在的记录。
            // 没有记录表时 `rowId` 就是 0，与加端口之前的行为一致。
            record = record.copy(id = rowId),
            evidence = result.toEvidence(),
            replayedFromId = null,
            rejectedReason = null,
            explanation = "已调用工具 ${result.tool.storageCode}，结果状态 ${result.status.storageCode}"
        )
    }

    /**
     * 查找可复用的历史 Observation。
     *
     * 复用条件必须同时满足：同一工具、同一参数摘要、本地已确认写回、且存在观察摘要哈希。
     * 仅凭参数相同就复用是危险的——上一次调用如果以超时结束，参数摘要仍然相同，
     * 复用会把一次失败永久固化成「已查过」。
     */
    private fun findReusable(state: RcaSharedState, request: ToolRequest): ToolCallRecord? = state.toolHistory
        .filter { history ->
            history.toolName == request.tool.storageCode &&
                history.paramsDigest == request.paramsDigest &&
                history.localCommitState == LocalCommitState.COMMITTED &&
                history.observationDigest != null &&
                history.observationSummary != null
        }
        .maxByOrNull(ToolCallRecord::decisionRound)

    private fun ToolResult.toRecord(request: ToolRequest): ToolCallRecord = ToolCallRecord(
        toolName = tool.storageCode,
        toolVersion = toolVersion,
        paramsDigest = paramsDigest,
        decisionRound = request.decisionRound,
        status = status,
        providerRequestId = providerRequestId,
        localCommitState = LocalCommitState.COMMITTED,
        observationDigest = observationDigest,
        observationSummary = observation?.summary,
        evidenceIds = listOf(EvidenceIdFactory.of(tool, paramsDigest)),
        replayedFromId = null,
        latencyMs = latencyMs,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        costUsd = costUsd
    )

    /**
     * 把工具结果映射成证据账本条目。
     *
     * 覆盖粒度与证据类型都由工具自身声明（[RcaToolName.evidenceCoverage] /
     * [RcaToolName.evidenceType]）而不是由模型指定：版本对照天然产出 cohort 证据，
     * 日志天然产出 episode 证据。让模型声明覆盖范围，就等于把「范围性结论有没有分母」
     * 交给模型自觉——这正是失败复盘一的成因。
     */
    private fun ToolResult.toEvidence(): EvidenceEntry? {
        val observation = observation ?: return null
        return EvidenceEntry(
            evidenceId = EvidenceIdFactory.of(tool, paramsDigest),
            type = tool.evidenceType,
            sourceTool = tool.storageCode,
            sourceRef = observation.sourceRef,
            coverageType = tool.evidenceCoverage,
            // 证据覆盖到哪个对象由回源指针表达；仲裁据此比对目标 Episode 是否落在覆盖范围内。
            coverageScope = observation.sourceRef,
            // 主体标识原样带进账本：稳定签名的组件字段要从这里推，丢在观察结果里就取不到了。
            subjectRefs = observation.subjectRefs,
            numerator = observation.errorSignatureCounts.values.sum().takeIf { it > 0 },
            denominator = observation.denominator,
            controlGroup = observation.controlGroup,
            observedFrom = observation.windowFrom,
            observedTo = observation.windowTo,
            timeWatermark = observation.timeWatermark,
            contentDigest = observationDigest ?: RcaCanonicalDigest.sha256(observation.summary),
            summary = observation.summary
        )
    }
}

/**
 * 守卫的产出。三件事分开表达：结果、审计记录、证据。
 *
 * [result] 为 null 表示这次没有发起真实调用——可能被预算拒绝（此时 [rejectedReason] 非空），
 * 也可能是复用了历史 Observation（此时 [replayedFromId] 非空）。调用方必须显式区分这两种情况，
 * 不允许把它们当成「查了没有」。
 */
data class GuardedToolOutcome(
    val result: ToolResult?,
    val record: ToolCallRecord?,
    val evidence: EvidenceEntry?,
    val replayedFromId: Long?,
    val rejectedReason: RcaStopReason?,
    val explanation: String
) {
    /** 调用被预算或截止时间拒绝。 */
    val rejected: Boolean get() = rejectedReason != null

    /** 复用了历史 Observation。审计上与真实调用区分开，用于统计冗余调用次数。 */
    val replayed: Boolean get() = replayedFromId != null

    /** 本次是否消耗了步数预算；复用与拒绝都不消耗。 */
    val consumedBudget: Boolean get() = !rejected && !replayed
}

/**
 * 证据 ID 工厂。ID 必须**确定性**：同一工具、同一参数摘要，无论重放多少次都得到同一个 ID。
 * 否则引用支持校验记录的 evidence ID 会与账本对不上，「可回源」这条就断了。
 */
object EvidenceIdFactory {
    private const val PREFIX = "EV"
    private const val DIGEST_PREFIX_LENGTH = 16

    fun of(tool: RcaToolName, paramsDigest: String): String =
        "$PREFIX-${tool.storageCode}-${paramsDigest.take(DIGEST_PREFIX_LENGTH)}"
}

/**
 * 工具调用 span 的确定性推导。span 由**调用四要素**推出，而不是随机生成。
 *
 * ## 为什么必须是确定性的
 *
 * `t_rca_tool_call` 的去重键本身就是 `(任务, 工具, 参数摘要, 轮次)`——这个键的语义是
 * 「**同一次逻辑调用**」。崩溃恢复会重跑同一个节点，于是同一次逻辑调用会被再走一遍；
 * 如果 span 是随机的，这**一次**调用在库里就有了两个互不相干的 span，
 * 去重键说「它们是同一次」，span 说「它们是两次」，两者直接互相矛盾。
 * 排障时按 trace 串调用链，会看到同一次调用出现两遍，且无法判断哪一遍是真发生过的。
 *
 * 这与 [EvidenceIdFactory] 是同一条纪律：凡是**代表「同一件事」的标识**都必须可复算，
 * 否则重放会让同一个东西在账本里出现多次。仓库里已有先例（确定性 evidence ID），
 * 这里只是把它用到链路标识上。
 *
 * ## 形状
 *
 * 取 `sha256` 的前 16 位小写十六进制，对齐 W3C Trace Context 的 span id 宽度
 * （也是 `chk_rca_tool_span` 的约束）。截断的是**摘要前缀**而不是重新编码，
 * 所以碰撞面与 64 位标识一致——对一个任务内的调用数（几十次）来说远远够用。
 */
object ToolSpanIdFactory {
    /** W3C Trace Context 的 span id 宽度：16 位小写十六进制字符。 */
    private const val SPAN_LENGTH = 16

    /**
     * 推导一次工具调用的 span。
     *
     * [traceId] 为 null 时**返回 null**：span 是从 trace 推出来的，没有 trace 就没有 span
     * （与 `chk_rca_tool_trace_span_pair` 同为一条不变式）。不允许退回成「用别的字段凑一个」——
     * 那样写出来的 span 会挂在一个不存在的 trace 下，比不写更糟。
     */
    fun of(traceId: String?, toolName: String, paramsDigest: String, decisionRound: Int): String? {
        if (traceId == null) return null
        return RcaCanonicalDigest
            .sha256("$traceId:$toolName:$paramsDigest:$decisionRound")
            .take(SPAN_LENGTH)
    }
}
