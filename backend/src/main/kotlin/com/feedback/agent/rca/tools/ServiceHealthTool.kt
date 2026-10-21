package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.EvidenceType
import com.feedback.agent.rca.domain.RcaToolName
import com.feedback.agent.rca.domain.ToolObservation
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.domain.ToolResultStatus

/**
 * 服务健康工具。失败语义：`PARTIAL`、`UPSTREAM_TIMEOUT`。
 *
 * 这个工具在 414 回放里承担的是**保留反证**的角色：第二轮查下来服务整体正常，
 * 错误只集中在 414 的请求上。也就是说它并不支持「服务端整体故障」，
 * 但它也不是缺口——「整体正常」本身是一条有效信息，它把排查方向从服务崩溃推向参数不兼容。
 *
 * 因此本工具刻意区分两个层次：
 * - `overallHealthy`：服务维度的整体错误率是否正常；
 * - 该服务是否存在**局部异常窗口**（`anomalyWindowFrom/To` 非空）。
 *
 * 只说「整体正常」而不给局部窗口，会诱导结论停在「没问题」；只报局部异常而不说整体正常，
 * 又会诱导结论走向「服务挂了」。两者必须同时给出，这是这条工具的设计重点。
 *
 * 上游只覆盖部分服务时返回 `PARTIAL`：既不能支持也不能反驳，只能记缺口。
 */
class ServiceHealthTool(
    private val port: ServiceHealthPort,
    private val clock: RcaClock = RcaClock.system()
) : ReadOnlyRcaTool() {

    override val tool: RcaToolName = RcaToolName.SERVICE_HEALTH
    override val toolVersion: String = TOOL_VERSION

    override fun query(request: ToolRequest): ToolQueryOutcome {
        val services = optionalStringList(request, PARAM_SERVICES)
        val windowFrom = optionalTime(request, PARAM_WINDOW_FROM)
            ?: throw IllegalArgumentException("服务健康查询必须给出时间窗起点 windowFrom")
        val windowTo = optionalTime(request, PARAM_WINDOW_TO)
            ?: throw IllegalArgumentException("服务健康查询必须给出时间窗终点 windowTo")

        val envelope = port.fetch(ServiceHealthQuery(services = services, windowFrom = windowFrom, windowTo = windowTo))
        envelope.upstreamErrorCode?.let { return translateUpstreamError(it) }

        val record = envelope.payload
            ?: return ToolQueryOutcome(
                status = ToolResultStatus.UNKNOWN,
                observation = null,
                failureDetail = "服务健康上游既未返回错误码也未返回数据，按 UNKNOWN 处理",
                providerRequestId = envelope.providerRequestId
            )

        if (record.rows.isEmpty()) {
            // 刻意**不给观察结果**：这条的含义是「上游没有这些服务的健康事实」，
            // 而不是「服务是健康的」。给了观察结果，基类会按「合法空结果即反证」
            // 把它当成反证去排除服务侧假设——那正是把「查不到」当成「没问题」。
            return ToolQueryOutcome(
                status = ToolResultStatus.NOT_FOUND,
                observation = null,
                failureDetail = "窗口内没有任何服务的健康数据，无法判断服务侧状态",
                providerRequestId = envelope.providerRequestId
            )
        }

        val totalDenominator = record.rows.sumOf(ServiceHealthRow::totalRequests)
        val anomalyRows = record.rows.filterNot(ServiceHealthRow::overallHealthy)
        val overallHealthy = anomalyRows.isEmpty()

        val gaps = buildList {
            if (record.partial) {
                add(
                    "服务健康上游只覆盖部分服务，缺失 ${record.missingServices.joinToString("/")}，" +
                        "不能据此排除服务侧原因"
                )
            }
            record.rows.filter { it.errorRate == null }.forEach { row ->
                add("服务 ${row.serviceName} 窗口内无请求，错误率不可计算")
            }
        }

        return ToolQueryOutcome(
            // 覆盖不全时降级为 PARTIAL：数据可用但不能用来反驳任何假设。
            status = if (record.partial) ToolResultStatus.PARTIAL else ToolResultStatus.SUCCESS_WITH_DATA,
            observation = ToolObservation(
                summary = buildString {
                    // 分母与错误量无条件带上：只给一句「整体正常」而不给数字，
                    // 复核时无法回答「凭什么叫正常」。异常窗口是上游按自身基线判定的，
                    // 不是本地用固定阈值算的，因此「有错误请求但没有异常窗口」
                    // 是一个合法且必须被看见的状态——它正是「有失败，但不是服务挂了」的表达。
                    append("${record.rows.size} 个服务在窗口内共 $totalDenominator 次请求、")
                    append("${record.rows.sumOf(ServiceHealthRow::errorRequests)} 次错误；")
                    if (overallHealthy) {
                        append("没有任何服务被上游标记为异常窗口，服务侧整体正常")
                    } else {
                        append("${anomalyRows.size} 个服务存在异常窗口：")
                        append(
                            anomalyRows.joinToString("；") {
                                "${it.serviceName}（$it.errorRequests/$it.totalRequests）"
                            }
                        )
                    }
                    record.rows.flatMap(ServiceHealthRow::sharedDependencies).distinct()
                        .takeIf(List<String>::isNotEmpty)
                        ?.let { append("。共同依赖：${it.joinToString("/")}") }
                },
                windowFrom = windowFrom,
                windowTo = windowTo,
                timeWatermark = envelope.watermark,
                subjectRefs = record.rows.map(ServiceHealthRow::serviceName),
                denominator = totalDenominator,
                newGaps = gaps,
                sourceRef = sourceRefOf(request)
            ),
            failureDetail = if (record.partial) "上游覆盖不全，按 PARTIAL 记录覆盖范围" else null,
            providerRequestId = envelope.providerRequestId
        )
    }

    /**
     * 回源指针按「被查询的服务集合」定位。
     *
     * 注意它**不是**应用级指针：服务与应用的归属关系需要一张注册表，MVP 没有。
     * 因此 [EvidenceScopeMatcher] 对本工具的结论是「不覆盖任何 Episode」——
     * 服务健康只能用于观察服务侧整体状态，不能作为归因依据。
     */
    override fun sourceRefOf(request: ToolRequest): String =
        "service-health://" + optionalStringList(request, PARAM_SERVICES).joinToString(",").ifBlank { "all" }

    companion object {
        const val TOOL_VERSION: String = "1.0.0"

        const val PARAM_SERVICES: String = "services"
        const val PARAM_WINDOW_FROM: String = "windowFrom"
        const val PARAM_WINDOW_TO: String = "windowTo"

        val EVIDENCE_TYPES: List<EvidenceType> = listOf(EvidenceType.SERVICE_HEALTH)
    }
}
