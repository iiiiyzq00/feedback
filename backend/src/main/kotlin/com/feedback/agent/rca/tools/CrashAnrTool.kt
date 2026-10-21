package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.EvidenceType
import com.feedback.agent.rca.domain.RcaToolName
import com.feedback.agent.rca.domain.ToolObservation
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.domain.ToolResultStatus

/**
 * Crash / ANR 工具。失败语义：`SYMBOL_MISSING`、`NOT_FOUND`。
 *
 * 这是「现象含 Crash 或 ANR 时必须取堆栈」这条强制证据的执行者。它产出的是**客户端侧**证据，
 * 与日志工具产出的服务端证据互相独立：堆栈指向客户端缺陷时，服务端日志应当看不到对应失败；
 * 反过来，如果服务端首错点成立而堆栈只显示上层解码失败，那说明客户端异常是下游表现。
 *
 * 因此本工具不做任何因果判断，只给出**去重后的堆栈指纹及其版本/机型分布**——
 * 判断留给调查循环和仲裁。分布必须给，因为「只在 414 机型上崩」和「全机型都崩」
 * 指向完全不同的结论。
 *
 * `SYMBOL_MISSING` 是缺口而不是反证：符号表缺失意味着堆栈无法定位到代码行，
 * 但崩溃本身确实发生了，不能因此说「没有崩溃」。
 */
class CrashAnrTool(
    private val port: CrashAnrPort,
    private val clock: RcaClock = RcaClock.system()
) : ReadOnlyRcaTool() {

    override val tool: RcaToolName = RcaToolName.CRASH_ANR
    override val toolVersion: String = TOOL_VERSION

    override fun query(request: ToolRequest): ToolQueryOutcome {
        val appId = requiredString(request, PARAM_APP_ID)
        val version = optionalString(request, PARAM_VERSION)
        val deviceModel = optionalString(request, PARAM_DEVICE_MODEL)
        val windowFrom = optionalTime(request, PARAM_WINDOW_FROM)
            ?: throw IllegalArgumentException("Crash/ANR 查询必须给出时间窗起点 windowFrom")
        val windowTo = optionalTime(request, PARAM_WINDOW_TO)
            ?: throw IllegalArgumentException("Crash/ANR 查询必须给出时间窗终点 windowTo")

        val envelope = port.fetch(
            CrashAnrQuery(
                appId = appId,
                version = version,
                deviceModel = deviceModel,
                windowFrom = windowFrom,
                windowTo = windowTo
            )
        )
        envelope.upstreamErrorCode?.let { return translateUpstreamError(it) }

        val record = envelope.payload
            ?: return ToolQueryOutcome(
                status = ToolResultStatus.UNKNOWN,
                observation = null,
                failureDetail = "Crash/ANR 上游既未返回错误码也未返回数据，按 UNKNOWN 处理",
                providerRequestId = envelope.providerRequestId
            )

        if (record.rows.isEmpty()) {
            // 崩溃平台没有记录：这是一条有效反证，可以排除客户端崩溃类假设。
            // 必须带观察结果，否则它只是失败详情，进不了反驳列表——
            // 「排除了客户端崩溃」这件事在取证链路上就没有可引用的落点。
            return ToolQueryOutcome(
                status = ToolResultStatus.NOT_FOUND,
                observation = ToolObservation(
                    summary = "窗口内 $appId 没有 Crash/ANR 记录，可排除客户端崩溃类假设",
                    windowFrom = windowFrom,
                    windowTo = windowTo,
                    timeWatermark = envelope.watermark,
                    sourceRef = sourceRefOf(request)
                ),
                failureDetail = "窗口内 $appId 没有 Crash/ANR 记录，可排除客户端崩溃类假设",
                providerRequestId = envelope.providerRequestId
            )
        }

        val symbolMissingRows = record.rows.filterNot(CrashStackRow::symbolAvailable)
        if (symbolMissingRows.size == record.rows.size) {
            // 全部堆栈都缺符号：这是缺口，不能得出「崩溃发生在某处」的结论。
            return ToolQueryOutcome(
                status = ToolResultStatus.SYMBOL_MISSING,
                observation = null,
                failureDetail = "${record.rows.size} 条堆栈均缺少符号表，无法定位到代码行，属证据缺口",
                providerRequestId = envelope.providerRequestId
            )
        }

        val ordered = record.rows.sortedByDescending(CrashStackRow::occurrences)
        val leading = ordered.first()
        val denominator = ordered.sumOf(CrashStackRow::occurrences)
        val gaps = buildList {
            if (symbolMissingRows.isNotEmpty()) {
                add("${symbolMissingRows.size} 条堆栈缺符号表，仅能给出指纹，未参与定位")
            }
            if (version == null) {
                add("调用未限定版本，堆栈分布跨全部版本，不能直接用于版本对比")
            }
        }

        return ToolQueryOutcome(
            status = ToolResultStatus.SUCCESS_WITH_DATA,
            observation = ToolObservation(
                summary = buildString {
                    append("窗口内共 $denominator 次崩溃，${ordered.size} 个堆栈指纹。")
                    append("占比最高：${leading.exceptionType} @ ${leading.topFrame}（${leading.occurrences} 次）")
                    if (leading.versions.isNotEmpty()) {
                        append("，版本分布 ${leading.versions.entries.joinToString("/") { "${it.key}:${it.value}" }}")
                    }
                    if (leading.deviceModels.isNotEmpty()) {
                        append(
                            "，机型分布 " +
                                leading.deviceModels.entries.joinToString("/") { "${it.key}:${it.value}" }
                        )
                    }
                },
                windowFrom = windowFrom,
                windowTo = windowTo,
                timeWatermark = envelope.watermark,
                subjectRefs = ordered.map(CrashStackRow::fingerprint),
                errorSignatureCounts = ordered.associate { it.fingerprint to it.occurrences },
                denominator = denominator,
                newGaps = gaps,
                sourceRef = sourceRefOf(request)
            ),
            providerRequestId = envelope.providerRequestId
        )
    }

    /**
     * 回源指针按应用定位。
     *
     * 主体标识是堆栈指纹，而指纹**不能当组件用**——它是缺陷的标识，不是服务的标识。
     * 因此 [EvidenceSourceRef.componentCandidates] 对本工具返回空列表，
     * 稳定签名的组件字段会回退到应用级，而不是被写成一个指纹字符串。
     */
    override fun sourceRefOf(request: ToolRequest): String =
        "crash-anr://${requiredString(request, PARAM_APP_ID)}"

    companion object {
        const val TOOL_VERSION: String = "1.0.0"

        const val PARAM_APP_ID: String = "appId"
        const val PARAM_VERSION: String = "version"
        const val PARAM_DEVICE_MODEL: String = "deviceModel"
        const val PARAM_WINDOW_FROM: String = "windowFrom"
        const val PARAM_WINDOW_TO: String = "windowTo"

        val EVIDENCE_TYPES: List<EvidenceType> = listOf(EvidenceType.CRASH_STACK)
    }
}
