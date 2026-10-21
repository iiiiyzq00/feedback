package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.EvidenceEntry
import com.feedback.agent.rca.domain.ProblemProfile
import com.feedback.agent.rca.domain.RcaToolName

/**
 * 待证明的目标问题对象。从 [ProblemProfile] 取出，只含判定覆盖关系真正需要的字段。
 */
data class EpisodeCoverageTarget(
    /** 本对象自己的合法日志 ID；为空表示没有现场日志，此时任何日志证据都不可能覆盖它。 */
    val logId: String?,
    val appId: String,
    val clusterId: Long?
) {
    companion object {
        fun of(profile: ProblemProfile): EpisodeCoverageTarget = EpisodeCoverageTarget(
            logId = profile.logId,
            appId = profile.appId,
            clusterId = profile.clusterId
        )
    }
}

/**
 * 证据覆盖判定：这条证据能不能用来证明这个 Episode。
 *
 * 放在工具层而不是仲裁节点里，是因为判定依赖**回源指针的格式**，而格式由工具层拥有
 * （解析见 [EvidenceSourceRef]）。把它写在仲裁节点里会让仲裁节点必须知道
 * `log-trace://` 这类前缀长什么样，上游换一次指针格式就要改仲裁逻辑。
 *
 * 判定逐工具给出，每条都有明确理由，而不是笼统的「前缀匹配」：
 *
 * - [RcaToolName.LOG_TRACE]：回源指针里的日志 ID 必须**等于本对象自己的日志 ID**。
 *   这是全表最严格的一档，也是「代表日志只能直接证明被查询的那一个 Episode」的落点。
 *   簇内其他成员的日志 ID 与本对象不同，因此不覆盖——这正是
 *   [com.feedback.agent.rca.domain.EpisodeAttributionLevel.SUPPORTED_INHERITED]
 *   与 DIRECT_CONFIRMED 必须分开的原因。
 * - [RcaToolName.VERSION_COHORT]：队列的分母按定义包含本对象所在应用与时间窗，
 *   以「应用一致 + 分母与对照组齐备」为准。它只能支撑继承级归因，
 *   不会让结论升级到直接确认——那一档要求单点级现场证据。
 * - [RcaToolName.SERVICE_HEALTH]：**永远不覆盖**。它按服务维度组织，而问题画像里没有
 *   「这个 Episode 属于哪些服务」的清单，因此无法确认覆盖关系。它只能用于观察服务侧
 *   整体状态，不能作为归因依据。这里是刻意判否而不是放宽：服务健康一旦被当成覆盖证据，
 *   「某个服务整体异常」就会被归因到并不属于该服务的 Episode 上。
 * - [RcaToolName.RELEASE_CHANGE] / [RcaToolName.CRASH_ANR]：以应用一致为准。
 *   发布记录与崩溃聚合都是按应用组织的，应用一致即视为覆盖本对象所在的应用。
 * - [RcaToolName.CONTENT_STATUS]：目标是资源 token，而本对象的事实里没有资源 token，
 *   因此无法确认覆盖关系，一律判为**不覆盖**。宁可判否也不要放宽：
 *   内容状态被用来支持归因时，必须由人确认资源范围。
 * - [RcaToolName.HISTORICAL_CASE]：**永远不覆盖**。历史案例是先验，不是本对象的现场证据，
 *   它可以把方向指得更准，但不能证明本次故障。
 * - 签名分布（[EvidenceSourceRef.SIGNATURE_SOURCE]）：比对本对象所在的联合调查候选 ID。
 *
 * 一处刻意保留的粗糙：队列证据不比指标键。要判指标键是否一致，需要一张
 * 「故障类型 → 指标」的注册表，MVP 阶段没有这张表，硬判反而会误杀。指标键仍写在
 * 回源指针里，人工复核时看得到；这个取舍在实现说明里要讲清楚，不能当成已解决。
 */
object EvidenceScopeMatcher {

    fun covers(evidence: EvidenceEntry, target: EpisodeCoverageTarget): Boolean {
        if (evidence.sourceTool == EvidenceSourceRef.SIGNATURE_SOURCE) {
            val clusterId = EvidenceSourceRef.clusterIdOf(evidence.sourceRef)
            return target.clusterId != null && clusterId == target.clusterId.toString()
        }

        val tool = EvidenceSourceRef.toolOf(evidence.sourceTool) ?: return false
        val ref = evidence.sourceRef
        return when (tool) {
            RcaToolName.LOG_TRACE ->
                target.logId != null && EvidenceSourceRef.logIdOf(ref) == target.logId

            // 队列证据要求分母与对照组齐备：缺任何一项，这条证据就没有「和谁比、比多少」，
            // 它连自己覆盖的范围都说不清，不能算作覆盖了本对象。
            RcaToolName.VERSION_COHORT ->
                EvidenceSourceRef.appIdOf(ref) == target.appId && evidence.cohortComplete

            RcaToolName.RELEASE_CHANGE,
            RcaToolName.CRASH_ANR -> EvidenceSourceRef.appIdOf(ref) == target.appId

            RcaToolName.SERVICE_HEALTH -> false
            RcaToolName.CONTENT_STATUS -> false
            RcaToolName.HISTORICAL_CASE -> false
        }
    }
}
