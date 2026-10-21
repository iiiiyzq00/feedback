package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.RcaClock
import com.feedback.agent.rca.domain.EvidenceType
import com.feedback.agent.rca.domain.RcaToolName
import com.feedback.agent.rca.domain.ToolObservation
import com.feedback.agent.rca.domain.ToolRequest
import com.feedback.agent.rca.domain.ToolResultStatus

/**
 * 发布变更工具（MVP 主线之一）。
 *
 * 失败语义只有三种：`NOT_FOUND`（窗口内没有发布记录）、`STALE`（水位落后，可能漏了刚发布的版本）、
 * `UPSTREAM_TIMEOUT`。三者的含义完全不同：
 *
 * - `NOT_FOUND` 是**有效反证**：「这个窗口内没有任何发布」，可以直接排除「新版本引入」假设。
 * - `STALE` 是**缺口**：变更记录本身可信，但可能还没同步到最新一次发布，
 *   所以它能支持但不能反驳，且必须记入缺口。
 *
 * 把它单独作为一条工具而不是复用「查一下数据库」的原因：发布记录是变更影响的唯一权威来源，
 * 414 回放里第三轮正是靠它发现「该版本重命名了 token 参数名」——这条证据既解释了首错点，
 * 也把因果链补齐。
 */
class ReleaseChangeTool(
    private val port: ReleaseChangePort,
    private val clock: RcaClock = RcaClock.system()
) : ReadOnlyRcaTool() {

    override val tool: RcaToolName = RcaToolName.RELEASE_CHANGE
    override val toolVersion: String = TOOL_VERSION

    override fun query(request: ToolRequest): ToolQueryOutcome {
        val appId = requiredString(request, PARAM_APP_ID)
        val component = optionalString(request, PARAM_COMPONENT)
        val windowFrom = optionalTime(request, PARAM_WINDOW_FROM)
            ?: throw IllegalArgumentException("发布变更查询必须给出时间窗起点 windowFrom")
        val windowTo = optionalTime(request, PARAM_WINDOW_TO)
            ?: throw IllegalArgumentException("发布变更查询必须给出时间窗终点 windowTo")

        val envelope = port.fetch(
            ReleaseChangeQuery(appId = appId, component = component, windowFrom = windowFrom, windowTo = windowTo)
        )
        envelope.upstreamErrorCode?.let { return translateUpstreamError(it) }

        val records = envelope.payload
            ?: return ToolQueryOutcome(
                status = ToolResultStatus.UNKNOWN,
                observation = null,
                failureDetail = "发布变更上游既未返回错误码也未返回数据，按 UNKNOWN 处理",
                providerRequestId = envelope.providerRequestId
            )

        // 水位早于窗口上界：可能漏掉窗口末尾刚发布的版本，属缺口而不是反证。
        val stale = envelope.watermark?.isBefore(windowTo) == true
        if (stale) {
            return ToolQueryOutcome(
                status = ToolResultStatus.STALE,
                observation = null,
                failureDetail = "变更数据水位 ${envelope.watermark} 早于查询窗口上界 $windowTo，可能漏掉最新发布",
                providerRequestId = envelope.providerRequestId
            )
        }

        if (records.isEmpty()) {
            // 合法且完整的查询没有发布记录：可以直接排除「新版本引入」这一类假设。
            // 这是关于现象的否定事实，所以必须带观察结果——否则它进不了反驳列表，
            // 「可排除」这句语义在系统里就没有落点。
            return ToolQueryOutcome(
                status = ToolResultStatus.NOT_FOUND,
                observation = ToolObservation(
                    summary = "窗口 [$windowFrom, $windowTo) 内 $appId 没有发布记录，可排除新版本引入",
                    windowFrom = windowFrom,
                    windowTo = windowTo,
                    timeWatermark = envelope.watermark,
                    sourceRef = sourceRefOf(request)
                ),
                failureDetail = "窗口 [$windowFrom, $windowTo) 内 $appId 没有发布记录，可排除新版本引入",
                providerRequestId = envelope.providerRequestId
            )
        }

        val ordered = records.sortedBy(ReleaseChangeRecord::releasedAt)
        val contractChanges = ordered.filter { record ->
            record.changeTypes.any { it in CONTRACT_CHANGE_TYPES }
        }

        return ToolQueryOutcome(
            status = ToolResultStatus.SUCCESS_WITH_DATA,
            observation = ToolObservation(
                summary = buildString {
                    append("窗口内共 ${ordered.size} 次发布：")
                    append(
                        ordered.joinToString("；") {
                            "${it.component}/${it.version} @${it.releasedAt}（${it.changeTypes.joinToString("/")}）"
                        }
                    )
                    if (contractChanges.isNotEmpty()) {
                        append("。其中 ${contractChanges.size} 次涉及契约变更，需核对接口参数兼容性")
                    }
                },
                windowFrom = windowFrom,
                windowTo = windowTo,
                timeWatermark = envelope.watermark,
                subjectRefs = ordered.flatMap { record -> listOf(record.component, record.version) }.distinct(),
                newGaps = buildList {
                    contractChanges
                        .filter { record -> record.changeDetail.isEmpty() }
                        .forEach { record ->
                            add("${record.component}/${record.version} 标记为契约变更但未提供变更明细，兼容性仍需确认")
                        }
                },
                sourceRef = sourceRefOf(request)
            ),
            providerRequestId = envelope.providerRequestId
        )
    }

    /** 回源指针按应用定位：发布记录是按应用组织的，组件只是它的一个筛选维度。 */
    override fun sourceRefOf(request: ToolRequest): String =
        "release-change://${requiredString(request, PARAM_APP_ID)}"

    companion object {
        const val TOOL_VERSION: String = "1.0.0"

        const val PARAM_APP_ID: String = "appId"
        const val PARAM_COMPONENT: String = "component"
        const val PARAM_WINDOW_FROM: String = "windowFrom"
        const val PARAM_WINDOW_TO: String = "windowTo"

        /** 会影响调用方契约的变更类型；只有这些才可能解释接口参数不兼容类故障。 */
        val CONTRACT_CHANGE_TYPES: Set<String> = setOf("API_CONTRACT", "SCHEMA", "CONFIG")

        val EVIDENCE_TYPES: List<EvidenceType> = listOf(EvidenceType.RELEASE_CHANGE)
    }
}
