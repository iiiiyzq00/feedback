package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.EvidenceType
import com.feedback.agent.rca.domain.RcaToolName
import com.feedback.agent.rca.domain.ToolObservation
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.domain.ToolResultStatus

/**
 * 内容状态工具。失败语义：`FORBIDDEN`、`RESOURCE_MISSING`。
 *
 * 它回答的问题是「资源本身是否可见」：内容是否已发布、可见性范围是否覆盖该用户、
 * CDN 是否就绪、当前生效的鉴权策略是哪一版。
 *
 * 与日志工具的分工必须讲清楚：日志告诉你**请求失败了**，内容状态告诉你**资源是否本来就不可见**。
 * 414 回放里这条工具没有成为主线，因为内容本身是已发布且可见的——失败发生在鉴权环节，
 * 不在内容环节。这个区分决定了「是内容问题还是鉴权问题」不会混为一谈。
 *
 * `FORBIDDEN` 是缺口：无权限查看内容状态不等于内容有问题。
 * `RESOURCE_MISSING` 是有效反证：资源不存在，现象自然有了解释，但这与「鉴权参数不兼容」是两个方向。
 */
class ContentStatusTool(
    private val port: ContentStatusPort,
    private val clock: RcaClock = RcaClock.system()
) : ReadOnlyRcaTool() {

    override val tool: RcaToolName = RcaToolName.CONTENT_STATUS
    override val toolVersion: String = TOOL_VERSION

    override fun query(request: ToolRequest): ToolQueryOutcome {
        val resourceToken = requiredString(request, PARAM_RESOURCE_TOKEN)
        val appId = requiredString(request, PARAM_APP_ID)
        val windowFrom = optionalTime(request, PARAM_WINDOW_FROM)
        val windowTo = optionalTime(request, PARAM_WINDOW_TO)

        val envelope = port.fetch(
            ContentStatusQuery(
                resourceToken = resourceToken,
                appId = appId,
                windowFrom = windowFrom,
                windowTo = windowTo
            )
        )
        envelope.upstreamErrorCode?.let { return translateUpstreamError(it) }

        val record = envelope.payload
            ?: return ToolQueryOutcome(
                status = ToolResultStatus.RESOURCE_MISSING,
                observation = null,
                failureDetail = "资源 $resourceToken 不存在或已下线",
                providerRequestId = envelope.providerRequestId
            )

        val anomalies = buildList {
            if (record.publishStatus != "PUBLISHED") add("发布状态为 ${record.publishStatus}")
            if (record.cdnStatus != "READY") add("CDN 状态为 ${record.cdnStatus}")
        }

        return ToolQueryOutcome(
            status = ToolResultStatus.SUCCESS_WITH_DATA,
            observation = ToolObservation(
                summary = buildString {
                    append("资源 $resourceToken：发布状态 ${record.publishStatus}")
                    append("，可见性 ${record.visibility}")
                    append("，CDN ${record.cdnStatus}")
                    record.authPolicyId?.let { append("，当前鉴权策略 $it") }
                    if (anomalies.isEmpty()) {
                        append("。内容侧无异常，失败点不在内容可见性环节")
                    } else {
                        append("。内容侧存在异常：${anomalies.joinToString("；")}")
                    }
                },
                windowFrom = windowFrom,
                windowTo = windowTo,
                timeWatermark = envelope.watermark,
                subjectRefs = listOfNotNull(resourceToken, record.authPolicyId),
                newGaps = if (record.authPolicyId == null) {
                    listOf("资源未返回生效的鉴权策略标识，无法与发布变更中的策略版本比对")
                } else {
                    emptyList()
                },
                sourceRef = sourceRefOf(request)
            ),
            providerRequestId = envelope.providerRequestId
        )
    }

    /**
     * 回源指针按资源 token 定位。
     *
     * 问题画像里没有资源 token，所以 [EvidenceScopeMatcher] 对本工具的结论是
     * 「不覆盖任何 Episode」：资源状态要成为归因依据，必须由人确认资源范围。
     */
    override fun sourceRefOf(request: ToolRequest): String =
        "content-status://${requiredString(request, PARAM_RESOURCE_TOKEN)}"

    companion object {
        const val TOOL_VERSION: String = "1.0.0"

        const val PARAM_RESOURCE_TOKEN: String = "resourceToken"
        const val PARAM_APP_ID: String = "appId"
        const val PARAM_WINDOW_FROM: String = "windowFrom"
        const val PARAM_WINDOW_TO: String = "windowTo"

        val EVIDENCE_TYPES: List<EvidenceType> = listOf(EvidenceType.CONTENT_STATUS)
    }
}
