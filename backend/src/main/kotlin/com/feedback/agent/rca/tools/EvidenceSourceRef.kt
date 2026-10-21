package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.RcaToolName

/**
 * 回源指针的解析。
 *
 * 为什么必须有这么一个类：回源指针的 URI 语法是**工具层自己定义的**，但它有两个外部消费者——
 * 覆盖判定（这条证据能不能证明这个 Episode）和稳定签名的组件推导（这条证据指向哪个组件）。
 * 如果两个消费者各自 `substringBefore('/')`，那么某天某个工具改成三段式指针时，
 * 只会有一处跟着改，另一处静默错位。所以语法只在这里解析一次。
 *
 * 指针形状一览（与各工具的 `sourceRefOf` 一一对应）：
 * - `log-trace://<logId>`
 * - `release-change://<appId>`
 * - `version-cohort://<appId>/<metricKey>`
 * - `service-health://<服务名,服务名>`
 * - `crash-anr://<appId>`
 * - `content-status://<resourceToken>`
 * - `historical-case://<signatureHint>`
 * - `cluster-signature://<clusterId>`（签名分布不是七类工具之一，由问题组织层产出）
 */
object EvidenceSourceRef {

    /** 签名分布证据的来源标识；它不是七类工具之一，单独识别。 */
    const val SIGNATURE_SOURCE: String = "CLUSTER_SIGNATURE_PROJECTION"

    private const val SCHEME_SEPARATOR: String = "://"
    private const val PATH_SEPARATOR: Char = '/'

    private const val LOG_TRACE_PREFIX: String = "log-trace://"
    private const val CLUSTER_SIGNATURE_PREFIX: String = "cluster-signature://"

    /** 去掉协议头之后的全部内容；指针里没有协议头时返回 null 而不是原串，避免把脏数据当合法指针。 */
    fun payloadOf(sourceRef: String): String? =
        sourceRef.substringAfter(SCHEME_SEPARATOR, "").takeIf {
            sourceRef.contains(SCHEME_SEPARATOR) && it.isNotBlank()
        }

    /** 指针指向的工具；签名分布不属于任何工具，返回 null。 */
    fun toolOf(sourceTool: String): RcaToolName? = RcaToolName.fromStorage(sourceTool)

    /** 日志 ID。只有日志指针含日志 ID，其余返回 null。 */
    fun logIdOf(sourceRef: String): String? =
        sourceRef.removePrefix(LOG_TRACE_PREFIX).takeIf { sourceRef.startsWith(LOG_TRACE_PREFIX) && it.isNotBlank() }

    /** 联合调查候选 ID。只有签名分布指针含它。 */
    fun clusterIdOf(sourceRef: String): String? =
        sourceRef.removePrefix(CLUSTER_SIGNATURE_PREFIX)
            .takeIf { sourceRef.startsWith(CLUSTER_SIGNATURE_PREFIX) && it.isNotBlank() }

    /**
     * 应用标识：指针的第一段。
     *
     * 只对**按应用组织**的工具（发布变更、版本对照、崩溃聚合）有意义。
     * 服务健康按服务组织、内容状态按资源组织，它们的这一段不是应用名，
     * 因此调用方必须按工具区分，不能无脑拿来比应用。
     */
    fun appIdOf(sourceRef: String): String? =
        payloadOf(sourceRef)?.substringBefore(PATH_SEPARATOR)?.takeIf(String::isNotBlank)

    /**
     * 该证据指向的组件候选，按可信度从高到低。
     *
     * 为什么需要它：稳定签名里的 `component` 是知识库与 Incident 关联的键之一，
     * 拿错会让同一条经验分裂成多条。而证据账本里没有一级的「组件」字段，
     * 只能从主体标识里推——所以这里把「哪些工具的主体标识可以当组件用」这条知识显式写下来。
     *
     * 可以用作组件的：
     * - 日志 Trace：主体标识按「首错点所在服务」置顶（见 [LogTraceTool]），
     *   它才是这条证据真正指向的组件；客户端的服务名排在后面，不能倒过来用。
     * - 服务健康：主体标识就是服务名。
     * - 发布变更：主体标识按「变更组件、版本」交替排列，第一条即组件。
     *
     * 不能当组件的：版本号（版本对照）、堆栈指纹（崩溃聚合）、资源 token（内容状态）、
     * 签名提示（历史案例）。这些返回空列表，宁可回退到应用级也不要拼出一个假组件。
     */
    fun componentCandidates(sourceTool: String, subjectRefs: List<String>): List<String> {
        val usable = when (toolOf(sourceTool)) {
            RcaToolName.LOG_TRACE,
            RcaToolName.SERVICE_HEALTH,
            RcaToolName.RELEASE_CHANGE -> true

            else -> false
        }
        return if (usable) subjectRefs.filter(String::isNotBlank) else emptyList()
    }
}
