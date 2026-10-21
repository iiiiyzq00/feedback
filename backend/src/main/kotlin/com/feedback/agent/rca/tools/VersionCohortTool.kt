package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.EvidenceCoverage
import com.feedback.agent.rca.domain.EvidenceType
import com.feedback.agent.rca.domain.RcaToolName
import com.feedback.agent.rca.domain.ToolObservation
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.domain.ToolResultStatus
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 版本对照工具（MVP 主线之一，也是唯一产出 [EvidenceCoverage.COHORT] 级证据的主线工具）。
 *
 * 它存在的理由是失败复盘一：单条新版本日志**不能**支持「故障集中在新版本」这种范围性结论。
 * 范围性结论需要分母、对照组和集中度，所以本工具的 Observation **强制携带**
 * `denominator`、`controlGroup`、`concentrationRatio` 三个字段，
 * 缺任何一个都返回 `INSUFFICIENT_SAMPLE` 而不是返回一个看起来能用但粒度不足的结果。
 *
 * 对照版本由调用方显式给出，工具不会「自动挑一个」：自动选对照组会让结论版本间不可比，
 * 也会让同一份数据在不同时间跑出不同结论。
 */
class VersionCohortTool(
    private val port: VersionCohortPort,
    private val clock: RcaClock = RcaClock.system()
) : ReadOnlyRcaTool() {

    override val tool: RcaToolName = RcaToolName.VERSION_COHORT
    override val toolVersion: String = TOOL_VERSION

    override fun query(request: ToolRequest): ToolQueryOutcome {
        val appId = requiredString(request, PARAM_APP_ID)
        val metricKey = requiredString(request, PARAM_METRIC_KEY)
        val controlVersion = requiredString(request, PARAM_CONTROL_VERSION)
        val windowFrom = optionalTime(request, PARAM_WINDOW_FROM)
            ?: throw IllegalArgumentException("版本对照必须给出时间窗起点 windowFrom")
        val windowTo = optionalTime(request, PARAM_WINDOW_TO)
            ?: throw IllegalArgumentException("版本对照必须给出时间窗终点 windowTo")

        val envelope = port.fetch(
            VersionCohortQuery(
                appId = appId,
                metricKey = metricKey,
                windowFrom = windowFrom,
                windowTo = windowTo,
                controlVersion = controlVersion
            )
        )
        envelope.upstreamErrorCode?.let { return translateUpstreamError(it) }

        val record = envelope.payload
            ?: return ToolQueryOutcome(
                status = ToolResultStatus.UNKNOWN,
                observation = null,
                failureDetail = "版本对照上游既未返回错误码也未返回数据，按 UNKNOWN 处理",
                providerRequestId = envelope.providerRequestId
            )

        val stale = envelope.watermark?.isBefore(windowTo) == true
        if (stale) {
            return ToolQueryOutcome(
                status = ToolResultStatus.STALE,
                observation = null,
                failureDetail = "版本会话数据水位 ${envelope.watermark} 早于窗口上界 $windowTo，" +
                    "集中度结论可能被迟到数据推翻",
                providerRequestId = envelope.providerRequestId
            )
        }

        if (!record.sampleSufficient) {
            return ToolQueryOutcome(
                status = ToolResultStatus.INSUFFICIENT_SAMPLE,
                observation = null,
                failureDetail = "上游标记 $metricKey 样本量不足，不得据此输出版本集中度结论",
                providerRequestId = envelope.providerRequestId
            )
        }

        val control = record.rows.firstOrNull { it.version == record.controlVersion }
            ?: return ToolQueryOutcome(
                status = ToolResultStatus.NOT_FOUND,
                observation = null,
                failureDetail = "对照版本 ${record.controlVersion} 在窗口内没有会话数据，无法构成对照",
                providerRequestId = envelope.providerRequestId
            )

        val candidates = record.rows.filter { it.version != record.controlVersion && it.errors > 0 }
        if (candidates.isEmpty()) {
            // 带观察结果：这是一条关于现象的否定事实（除对照版本外确实没有版本出现过该错误），
            // 它有资格进入反驳列表，把「故障集中在新版本」这类假设排除掉。
            // 对照上面的 STALE / INSUFFICIENT_SAMPLE：那两条是缺口，不给观察结果。
            return ToolQueryOutcome(
                status = ToolResultStatus.SUCCESS_EMPTY,
                observation = ToolObservation(
                    summary = "除对照版本 ${record.controlVersion} 外没有任何版本出现 $metricKey 错误，" +
                        "不支持版本集中假设",
                    windowFrom = windowFrom,
                    windowTo = windowTo,
                    timeWatermark = envelope.watermark,
                    subjectRefs = record.rows.map(VersionCohortRow::version),
                    sourceRef = sourceRefOf(request)
                ),
                failureDetail = "除对照版本外没有任何版本出现 $metricKey 错误，不支持版本集中假设",
                providerRequestId = envelope.providerRequestId
            )
        }

        // 集中度按「非对照版本中错误量最大的那个版本」计算，规则确定且可复算。
        val leading = candidates.maxBy(VersionCohortRow::errors)
        val denominator = record.rows.sumOf(VersionCohortRow::sessions)
        val numerator = leading.errors
        val concentration = BigDecimal.valueOf(numerator)
            .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP)
        val controlRate = control.failureRate
        val leadingRate = leading.failureRate

        val gaps = buildList {
            val missingVersions = record.rows.filter { it.failureRate == null }.map(VersionCohortRow::version)
            if (missingVersions.isNotEmpty()) {
                add("版本 ${missingVersions.joinToString("/")} 会话量为 0，失败率无法计算，未纳入分母")
            }
            if (controlRate == null) {
                add("对照版本 ${record.controlVersion} 会话量为 0，无法给出对照失败率")
            }
        }

        return ToolQueryOutcome(
            status = ToolResultStatus.SUCCESS_WITH_DATA,
            observation = ToolObservation(
                summary = buildString {
                    append("$metricKey 在窗口 [$windowFrom, $windowTo) 内共 $denominator 次会话：")
                    append("版本 ${leading.version} 失败 $numerator 次")
                    leadingRate?.let { append("（失败率 $it）") }
                    append("，对照版本 ${record.controlVersion} 失败 ${control.errors} 次")
                    controlRate?.let { append("（失败率 $it）") }
                    append("。集中度 $concentration")
                },
                windowFrom = windowFrom,
                windowTo = windowTo,
                timeWatermark = envelope.watermark,
                subjectRefs = record.rows.map(VersionCohortRow::version),
                denominator = denominator,
                controlGroup = record.controlVersion,
                concentrationRatio = concentration,
                newGaps = gaps,
                sourceRef = sourceRefOf(request)
            ),
            providerRequestId = envelope.providerRequestId
        )
    }

    /**
     * 回源指针按「应用 + 指标键」定位。
     *
     * 两段式是刻意的：只给应用会让「同一个应用的另一个指标」共用同一个指针，
     * 覆盖判定就无法区分；指标键写进指针也让人工复核时看得到这条证据在说哪个指标。
     */
    override fun sourceRefOf(request: ToolRequest): String =
        "version-cohort://${requiredString(request, PARAM_APP_ID)}/${requiredString(request, PARAM_METRIC_KEY)}"

    companion object {
        const val TOOL_VERSION: String = "1.0.0"

        const val PARAM_APP_ID: String = "appId"
        const val PARAM_METRIC_KEY: String = "metricKey"
        const val PARAM_CONTROL_VERSION: String = "controlVersion"
        const val PARAM_WINDOW_FROM: String = "windowFrom"
        const val PARAM_WINDOW_TO: String = "windowTo"

        val EVIDENCE_TYPES: List<EvidenceType> = listOf(EvidenceType.VERSION_COHORT)
    }
}
