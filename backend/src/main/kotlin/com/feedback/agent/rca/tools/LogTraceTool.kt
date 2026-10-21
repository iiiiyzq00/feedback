package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.EvidenceType
import com.feedback.agent.rca.domain.FaultPointRelation
import com.feedback.agent.rca.domain.FirstFaultPoint
import com.feedback.agent.rca.domain.RcaToolName
import com.feedback.agent.rca.domain.ToolObservation
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.domain.ToolResultStatus

/**
 * 日志 Trace 工具（MVP 主线之一）。
 *
 * 职责边界：上游按合法 log ID 给出 Trace，本工具负责**时间窗裁剪、Trace 去重、首错点定位、
 * 错误签名提取和回源 ID 生成**。脱敏由上游完成，这里不再改内容，避免两处脱敏规则不一致。
 *
 * 三个容易做错的地方：
 *
 * 1. **首错点不等于客户端的现象点**。用户报「课程视频黑屏」，但 Trace 里最早观察到的失败
 *    在资源鉴权服务返回 HTTP 400。工具必须按事件时间给出最早失败点，而不是把客户端异常
 *    当根因。414 回放的第一轮就是靠这条证据反驳了「纯客户端解码」假设。
 *
 * 2. **`SUCCESS_EMPTY` 与 `NOT_FOUND` 语义不同**。前者是「合法且完整的查询，窗口内确实没有」，
 *    可以作为结论依据；后者是「上游没有这条事实」，说明日志 ID 本身有问题。
 *
 * 3. **超时不等于没有**。上游超时翻译成 `UPSTREAM_TIMEOUT`，被记成证据缺口。
 *    失败复盘二讲的就是一旦把超时当空结果，Agent 就会退回去用历史案例锚定结论。
 */
class LogTraceTool(
    private val port: LogTracePort,
    private val clock: RcaClock = RcaClock.system()
) : ReadOnlyRcaTool() {

    override val tool: RcaToolName = RcaToolName.LOG_TRACE
    override val toolVersion: String = TOOL_VERSION

    override fun query(request: ToolRequest): ToolQueryOutcome {
        val logId = requiredString(request, PARAM_LOG_ID)
        val windowFrom = optionalTime(request, PARAM_WINDOW_FROM)
        val windowTo = optionalTime(request, PARAM_WINDOW_TO)

        val envelope = port.fetch(LogTraceQuery(logId = logId, windowFrom = windowFrom, windowTo = windowTo))
        envelope.upstreamErrorCode?.let { return translateUpstreamError(it) }

        val record = envelope.payload
            ?: return ToolQueryOutcome(
                status = ToolResultStatus.NOT_FOUND,
                // 刻意**不给观察结果**：这个 NOT_FOUND 的含义是「上游没有这条事实」，
                // 而不是「窗口内确实没有日志」。给了观察结果，基类就会按「合法空结果即反证」
                // 把它当成反证；那是失败复盘二的翻版——把「查不到」当成「没问题」。
                // 对照下面的 SUCCESS_EMPTY：那一条才是真正的否定事实，所以它带观察结果。
                observation = null,
                failureDetail = "上游没有 log ID 为 $logId 的 Trace 记录",
                providerRequestId = envelope.providerRequestId
            )

        // 时间窗裁剪：闭开区间 [from, to)，边界一律用事件时间比较。
        val spansInWindow = record.spans.filter { span ->
            (windowFrom == null || !span.startedAt.isBefore(windowFrom)) &&
                (windowTo == null || span.startedAt.isBefore(windowTo))
        }

        if (spansInWindow.isEmpty()) {
            // 合法且完整的查询返回为空——这是可用结论，不是失败。
            //
            // 必须带观察结果，否则「合法空结果即反证」这条规则根本不会执行：
            // 基类的回填只在观察结果存在时发生（observation 为 null 时它拿不到落点）。
            // 这条具体含义是「这个日志对应的窗口内确实没有链路」，是关于现象的否定事实，
            // 因此可以进反驳列表。
            return ToolQueryOutcome(
                status = ToolResultStatus.SUCCESS_EMPTY,
                observation = ToolObservation(
                    summary = "Trace ${record.traceId} 在请求窗口内没有任何 span，属合法空结果",
                    windowFrom = windowFrom,
                    windowTo = windowTo,
                    timeWatermark = envelope.watermark,
                    sourceRef = sourceRefOf(request)
                ),
                failureDetail = "Trace ${record.traceId} 在请求窗口内没有任何 span，属合法空结果",
                providerRequestId = envelope.providerRequestId
            )
        }

        val deduplicated = deduplicate(spansInWindow)
        val firstFault = locateFirstFaultPoint(deduplicated)
        val signatureCounts = errorSignatureCounts(deduplicated)
        val gaps = buildList {
            if (record.partial) {
                add("Trace ${record.traceId} 因采样或分区缺失不完整，首错点可能不是真正的最早失败点")
            }
            if (firstFault == null) {
                add("窗口内没有失败的 span，但客户端现象存在：需要换时间窗或引入崩溃堆栈证据")
            }
        }

        val summary = buildString {
            append(
                "Trace ${record.traceId} 共 ${record.spans.size} 个 span，" +
                    "窗口内 ${spansInWindow.size} 个，去重后 ${deduplicated.size} 个"
            )
            if (firstFault != null) {
                append("；最早失败在 ${firstFault.serviceName}/${firstFault.operationName}")
                firstFault.endpoint?.let { append(" 接口 $it") }
                firstFault.statusCode?.let { append(" 返回 HTTP $it") }
            }
        }

        return ToolQueryOutcome(
            status = if (record.partial) ToolResultStatus.PARTIAL else ToolResultStatus.SUCCESS_WITH_DATA,
            observation = ToolObservation(
                summary = summary,
                windowFrom = windowFrom,
                windowTo = windowTo,
                timeWatermark = envelope.watermark,
                // 首错点所在服务置顶：它才是这条 Trace 真正指向的组件。稳定签名的组件字段
                // 从主体标识推导，若按 span 顺序摆，第一个会是客户端服务名（study-client），
                // 组件就会被污染成「现象的观察者」而不是「故障的发生者」。
                subjectRefs = (
                    listOfNotNull(firstFault?.serviceName) +
                        deduplicated.mapNotNull(LogSpan::serviceName)
                    ).distinct(),
                firstFaultPoint = firstFault,
                errorSignatureCounts = signatureCounts,
                newGaps = gaps,
                sourceRef = sourceRefOf(request)
            ),
            failureDetail = if (record.partial) "Trace 不完整，按 PARTIAL 记录覆盖不全" else null,
            providerRequestId = envelope.providerRequestId
        )
    }

    /** 回源指针按日志 ID 定位：同一份 Trace 只有一个合法 ID，与时间窗无关。 */
    override fun sourceRefOf(request: ToolRequest): String =
        "log-trace://${requiredString(request, PARAM_LOG_ID)}"

    /**
     * Trace 去重：同一 `服务 + 操作 + 接口 + 错误码` 只保留最早出现的那一条。
     *
     * 重复 span 通常来自重试和并发扇出，保留全部会让错误签名分布被重试次数放大，
     * 而重试次数取决于客户端策略，与故障本身无关。
     */
    private fun deduplicate(spans: List<LogSpan>): List<LogSpan> = spans
        .sortedBy(LogSpan::startedAt)
        .distinctBy { span ->
            listOf(span.serviceName, span.operationName, span.endpoint ?: "", span.errorCode ?: "").joinToString("|")
        }

    /**
     * 定位首错点：所有失败 span 中事件时间最早的那一个。
     *
     * 关系判定取保守值——只有当该 span 自身失败时才标 [FaultPointRelation.DIRECT]；
     * 上游服务先失败、本服务随之失败的情况记为 [FaultPointRelation.UPSTREAM]。
     * 因果关系未经确认的一律不写 DIRECT，防止把并发失败误当成因果。
     */
    private fun locateFirstFaultPoint(spans: List<LogSpan>): FirstFaultPoint? {
        val earliestFailure = spans.filter(LogSpan::failed).minByOrNull(LogSpan::startedAt) ?: return null
        return FirstFaultPoint(
            serviceName = earliestFailure.serviceName,
            operationName = earliestFailure.operationName,
            endpoint = earliestFailure.endpoint,
            statusCode = earliestFailure.statusCode,
            occurredAt = earliestFailure.startedAt,
            relation = FaultPointRelation.DIRECT,
            errorCode = earliestFailure.errorCode
        )
    }

    /**
     * 错误签名分布：按 `错误码@接口` 归一后计数。
     *
     * 归一的意义在于把「同因不同表述」压到同一个键上：参数名不兼容会在不同调用点产生
     * 措辞不同的错误，但错误码与接口是稳定的，用它做键才能看出集中度。
     */
    private fun errorSignatureCounts(spans: List<LogSpan>): Map<String, Long> = spans
        .filter(LogSpan::failed)
        .groupingBy { span -> "${span.errorCode ?: "UNKNOWN"}@${span.endpoint ?: span.operationName}" }
        .eachCount()
        .mapValues { (_, count) -> count.toLong() }

    companion object {
        /** 上游接口版本；上游契约变更时递增，落库供事后对账。 */
        const val TOOL_VERSION: String = "1.0.0"

        const val PARAM_LOG_ID: String = "logId"
        const val PARAM_WINDOW_FROM: String = "windowFrom"
        const val PARAM_WINDOW_TO: String = "windowTo"

        /** 该工具产出的证据类型；适配层据此构造证据账本条目。 */
        val EVIDENCE_TYPES: List<EvidenceType> = listOf(EvidenceType.LOG_TRACE, EvidenceType.FIRST_FAULT_POINT)
    }
}
