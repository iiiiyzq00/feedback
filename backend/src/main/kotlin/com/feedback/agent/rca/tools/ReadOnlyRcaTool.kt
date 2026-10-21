package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.RcaToolInvoker
import com.feedback.agent.rca.domain.ToolObservation
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.domain.ToolResult
import com.feedback.agent.rca.domain.ToolResultStatus
import java.time.LocalDateTime

/**
 * 只读工具模板。
 *
 * 子类只负责「查询 + 归一」，基类统一承担四件事，避免每个工具各写一遍而出现语义不一致：
 *
 * 1. **参数摘要**：调用方没传摘要时由基类按规范形式补齐，保证去重键稳定。
 * 2. **异常翻译**：子类抛出的任何异常都转成 [ToolResultStatus.UNKNOWN]，绝不让异常穿到编排层。
 *    这一点很关键——异常穿透会丢掉失败语义，而「未知」和「没有」在证据判断上是相反的意思。
 * 3. **计时**：统一记录耗时，供预算与可观测性使用。
 * 4. **Observation 摘要哈希**：落库供恢复时判断该步骤是否已有完整产出。
 *
 * 还有一条规则由基类统一执行：**「合法空结果」只有带上观察结果才能构成反证**。
 * 回填支持/反驳的 [applyExpectation] 只在观察结果存在时才有落点，所以子类在返回
 * `SUCCESS_EMPTY` / `NOT_FOUND` 时必须自己判断这一次的空结果是不是「关于现象的否定事实」：
 *
 * - 是（日志窗口内确实没有链路、崩溃平台没有崩溃记录、窗口内没有发布、除对照版本外没有集中错误）
 *   → 落一条最小观察结果，该调用进入**反驳列表**，可以排除对应假设。
 * - 否（上游没有这条事实：合法日志 ID 不存在、没有健康遥测、没有已验证案例）
 *   → 保持观察结果为 null。这不是反证，也不该被当成反证；把「查不到」当成「没问题」
 *   正是失败复盘二的成因。
 *
 * 所以观察结果在这里不只是给模型看的摘要，它还是「这条调用有没有资格反驳假设」的载体。
 */
abstract class ReadOnlyRcaTool : RcaToolInvoker {

    final override fun invoke(request: ToolRequest): ToolResult {
        val paramsDigest = request.paramsDigest.ifBlank { RcaCanonicalDigest.ofParams(request.params) }
        val startedAtNanos = System.nanoTime()
        val outcome = try {
            query(request)
        } catch (ex: Exception) {
            ToolQueryOutcome(
                status = ToolResultStatus.UNKNOWN,
                observation = null,
                failureDetail = "工具执行异常 ${ex.javaClass.simpleName}：按 UNKNOWN 处理，不得当作空结果"
            )
        }
        val latencyMs = ((System.nanoTime() - startedAtNanos) / NANOS_PER_MILLI).toInt()
        // 摘要必须基于最终落库的那个 Observation 计算，否则恢复时用摘要比对会永远对不上。
        val observation = outcome.observation?.let { applyExpectation(request, outcome.status, it) }
        return ToolResult(
            tool = tool,
            toolVersion = toolVersion,
            status = outcome.status,
            paramsDigest = paramsDigest,
            decisionRound = request.decisionRound,
            observation = observation,
            failureDetail = outcome.failureDetail,
            providerRequestId = outcome.providerRequestId,
            observationDigest = observation?.let(RcaCanonicalDigest::ofObservation),
            latencyMs = latencyMs,
            inputTokens = outcome.inputTokens,
            outputTokens = outcome.outputTokens,
            costUsd = outcome.costUsd
        )
    }

    /**
     * 依据「调用方声明的检验意图」回填 Observation 的支持/反驳指向。
     *
     * 这里实现的是一条硬规则，也是项目里「证据缺口不等于没有问题」的落点：
     *
     * - 取到了数据：该调用声明要支持的目标假设进入支持列表（机制链是否成立仍由仲裁复核）。
     * - 合法空结果（`SUCCESS_EMPTY` / `NOT_FOUND`）：这才是**真正的反证**——查询确实做了，
     *   现象确实不存在，因此进入反驳列表。
     * - 超时、无权限、样本不足等缺口：既不支持也不反驳，只能写进 `newGaps`。
     *
     * 把这条规则放在基类而不是各工具里，是为了避免某个工具单独把超时当成反驳：
     * 那正是失败复盘二的成因——日志超时后 Agent 退回去用历史案例锚定结论。
     */
    private fun applyExpectation(
        request: ToolRequest,
        status: ToolResultStatus,
        observation: ToolObservation
    ): ToolObservation {
        val tested = request.params[ToolRequest.PARAM_TESTS_HYPOTHESIS]?.toString()?.takeIf(String::isNotBlank)
            ?: return observation
        val conclusion =
            status.hasData || status == ToolResultStatus.SUCCESS_EMPTY || status == ToolResultStatus.NOT_FOUND
        return when {
            !conclusion -> observation.copy(
                newGaps = observation.newGaps +
                    "检验 $tested 的调用以 ${status.storageCode} 结束，属证据缺口，不能作为反驳依据"
            )

            // PARTIAL 与 STALE 既有数据又是缺口：数据可用但不能据此排除任何假设。
            status.evidenceGap -> observation.copy(
                newGaps = observation.newGaps +
                    "检验 $tested 的调用以 ${status.storageCode} 结束，数据不完整或已过期，不能作为反驳依据"
            )

            status.hasData -> observation.copy(supportsHypotheses = observation.supportsHypotheses + tested)

            else -> observation.copy(refutesHypotheses = observation.refutesHypotheses + tested)
        }
    }

    /** 子类实现：执行上游查询并把响应归一成观察结果。允许抛异常，由基类翻译。 */
    protected abstract fun query(request: ToolRequest): ToolQueryOutcome

    /**
     * 本次调用的回源指针。
     *
     * 由各工具实现而不是基类拼：指针语法归工具层所有（解析见 [EvidenceSourceRef]）。
     * 之所以提到基类契约里，是因为「合法空结果」那一路由子类直接返回观察结果，
     * 指针必须由同一个方法给出，两处各写一遍迟早会漂移。
     */
    protected abstract fun sourceRefOf(request: ToolRequest): String

    /** 从参数里取必需的字符串；缺失即视为调用方构造了非法请求。 */
    protected fun requiredString(request: ToolRequest, key: String): String =
        request.params[key]?.toString()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("工具 ${tool.storageCode} 缺少必需参数 $key")

    /** 从参数里取可选的字符串。 */
    protected fun optionalString(request: ToolRequest, key: String): String? =
        request.params[key]?.toString()?.takeIf(String::isNotBlank)

    /** 从参数里取可选的时间；格式必须是 ISO-8601，解析失败即视为非法请求。 */
    protected fun optionalTime(request: ToolRequest, key: String): LocalDateTime? =
        optionalString(request, key)?.let(LocalDateTime::parse)

    /** 从参数里取可选的字符串列表。 */
    protected fun optionalStringList(request: ToolRequest, key: String): List<String> =
        when (val raw = request.params[key]) {
            null -> emptyList()
            is List<*> -> raw.mapNotNull { it?.toString() }
            else -> listOf(raw.toString())
        }

    /**
     * 把上游错误码翻译成统一的工具状态。
     *
     * 上游码到统一语义的映射必须集中在一处：如果每个工具各写一套 when，
     * 上游改一次编码就会有工具漏改，进而把「超时」说成「没有」。
     */
    protected fun translateUpstreamError(code: String): ToolQueryOutcome {
        val status = UPSTREAM_ERROR_MAPPING[code.uppercase()] ?: ToolResultStatus.UNKNOWN
        return ToolQueryOutcome(
            status = status,
            observation = null,
            failureDetail = "上游返回 $code，统一语义映射为 ${status.storageCode}"
        )
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /**
         * 上游错误码到统一状态的映射表。新增上游码时必须同时补这里，
         * 否则会落到 UNKNOWN 并被记录为证据缺口——这是刻意选择的保守默认值。
         */
        val UPSTREAM_ERROR_MAPPING: Map<String, ToolResultStatus> = mapOf(
            "TIMEOUT" to ToolResultStatus.UPSTREAM_TIMEOUT,
            "UPSTREAM_TIMEOUT" to ToolResultStatus.UPSTREAM_TIMEOUT,
            "GATEWAY_TIMEOUT" to ToolResultStatus.UPSTREAM_TIMEOUT,
            "FORBIDDEN" to ToolResultStatus.FORBIDDEN,
            "UNAUTHORIZED" to ToolResultStatus.FORBIDDEN,
            "PERMISSION_DENIED" to ToolResultStatus.FORBIDDEN,
            "INVALID_LOG_ID" to ToolResultStatus.INVALID_LOG_ID,
            "SCHEMA_INVALID" to ToolResultStatus.SCHEMA_INVALID,
            "STALE" to ToolResultStatus.STALE,
            "INSUFFICIENT_SAMPLE" to ToolResultStatus.INSUFFICIENT_SAMPLE,
            "SYMBOL_MISSING" to ToolResultStatus.SYMBOL_MISSING,
            "RESOURCE_MISSING" to ToolResultStatus.RESOURCE_MISSING
        )
    }
}

/**
 * 工具查询的归一结果。子类只填这一组字段，不接触落库与计时细节。
 */
data class ToolQueryOutcome(
    val status: ToolResultStatus,
    val observation: ToolObservation?,
    val failureDetail: String? = null,
    val providerRequestId: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val costUsd: java.math.BigDecimal? = null
) {
    init {
        // 有数据必须有 Observation，失败必须有原因：两者都不允许含糊。
        //
        // 反过来不成立：**合法的空结果也必须能带 Observation**。`SUCCESS_EMPTY` / `NOT_FOUND`
        // 正是「关于现象的否定事实」（崩溃平台没有崩溃记录、窗口内没有发布），
        // 而否定事实是靠 Observation 进入反驳列表的——观察结果为空，这次调用就没有反驳的落点，
        // 「我们排除了这个方向」在证据链上会变成一个说不出口的结论。
        // 所以这里只约束一个方向：有数据不许没有观察结果。
        require(!status.hasData || observation != null) {
            "状态 ${status.storageCode} 声称有数据却没有 Observation"
        }
        require(status.hasData || failureDetail != null || status.evidenceGap) {
            "非数据类状态必须给出失败说明或标记为证据缺口"
        }
    }

    /** 失败语义：把状态直接当作失败码回传，供强制证据计划记录缺口原因。 */
    val failureSemantic: ToolResultStatus? get() = if (status.hasData) null else status
}
