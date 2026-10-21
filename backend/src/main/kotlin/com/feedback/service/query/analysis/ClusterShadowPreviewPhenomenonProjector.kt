package com.feedback.service.query.analysis

import com.feedback.application.port.problem.analysis.ClusterShadowSafeProblemEvidence
import com.feedback.model.po.cluster.ClusterShadowCandidateMemberPo
import com.feedback.service.cluster.AtomicProblemExtractorV4
import com.feedback.service.cluster.AtomicProblemProfile
import com.feedback.service.cluster.ClusterClassificationInput
import com.feedback.service.cluster.DeterministicClusterNamePolicy
import com.feedback.service.cluster.ProblemClassificationStatus
import org.springframework.stereotype.Component
import java.security.MessageDigest

/** 从 PASS 脱敏证据重建可读症状并对照 generation 哈希；不执行召回、向量计算或归簇。 */
@Component
class ClusterShadowPreviewPhenomenonProjector {
    private val extractor = AtomicProblemExtractorV4()
    private val names = DeterministicClusterNamePolicy()

    fun project(
        clusterKeyHash: String,
        clusterNameHash: String,
        members: List<ClusterShadowCandidateMemberPo>,
        evidenceBySourceId: Map<Long, ClusterShadowSafeProblemEvidence>
    ): ClusterShadowReadableProblem {
        val profiles = members.flatMap { member ->
            val evidence = evidenceBySourceId[member.feedbackSourceId]
                ?: previewUnavailable("CLUSTER_SHADOW_PREVIEW_EVIDENCE_MISSING")
            verifiedProfiles(member, evidence)
        }
        val matching = profiles.filter { profile ->
            profile.identityKey?.let { hash("cluster-key", "symptom:$it") } == clusterKeyHash &&
                hash("cluster-name", names.name(profile)) == clusterNameHash
        }.mapNotNull { profile ->
            val application = profile.applicationName?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val phenomenon = profile.canonicalSymptom?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            ClusterShadowReadableProblem(application, safeDisplay(phenomenon), qualifier(profile))
        }.toSet()
        if (matching.size != 1) previewUnavailable("CLUSTER_SHADOW_PREVIEW_NAME_REHYDRATION_FAILED")
        return matching.single()
    }

    private fun verifiedProfiles(
        member: ClusterShadowCandidateMemberPo,
        evidence: ClusterShadowSafeProblemEvidence
    ): List<AtomicProblemProfile> {
        val ambiguityCandidates = if (evidence.applicationKey == null) listOf(false, true) else listOf(false)
        return ambiguityCandidates.mapNotNull { ambiguous ->
            val profile = extractor.extract(ClusterClassificationInput(
                sourceId = evidence.sourceId,
                applicationKey = evidence.applicationKey,
                applicationName = evidence.applicationName,
                title = evidence.title,
                description = evidence.description,
                functionModule = evidence.functionModule,
                issueCategory = evidence.issueCategory,
                applicationAmbiguous = ambiguous,
                sourceChannel = evidence.channel
            ))
            if (profile.status != ProblemClassificationStatus.ACTIONABLE ||
                member.applicationHash != hashNullable("application", profile.applicationKey) ||
                member.failureModeHash != hashNullable("failure-mode", profile.failureMode) ||
                member.targetHash != hashNullable("target", profile.target) ||
                member.actionHash != hashNullable("action", profile.action) ||
                member.errorSignatureHash != hashNullable("error-signature", profile.errorSignature)) null else profile
        }.also {
            if (it.isEmpty()) previewUnavailable("CLUSTER_SHADOW_PREVIEW_EVIDENCE_DIGEST_MISMATCH")
        }
    }

    private fun hashNullable(domain: String, value: String?): String? =
        value?.takeIf(String::isNotBlank)?.let { hash(domain, it) }

    private fun hash(domain: String, value: String): String = MessageDigest.getInstance("SHA-256")
        .digest("cluster-shadow-generation-v1\u0000$domain\u0000$value".toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** 让运维签名留在已校验的哈希边界内，但绝不渲染标识符或哈希。 */
    private fun safeDisplay(value: String): String = value
        .replace(UUID_OR_HASH, "[标识已隐藏]")
        .replace(LONG_NUMBER, "[编号已隐藏]")

    private fun qualifier(profile: AtomicProblemProfile): String? = listOfNotNull(
        profile.target?.let { "对象：${safeDisplay(it)}" },
        profile.action?.let { "场景：${ACTION_LABELS[it] ?: it}" }
    ).joinToString("；").ifBlank { null }

    companion object {
        private val UUID_OR_HASH = Regex(
            "(?i)(?:[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}|[0-9a-f]{16,})"
        )
        private val LONG_NUMBER = Regex("[0-9]{8,}")
        private val ACTION_LABELS = mapOf(
            "UPLOAD" to "上传", "DOWNLOAD" to "下载", "SUBMIT" to "提交", "PLAY" to "播放",
            "START" to "启动", "CLICK" to "点击", "ENTER" to "进入", "OPEN" to "打开"
        )
    }
}

data class ClusterShadowReadableProblem(
    val application: String,
    val phenomenon: String,
    val qualifier: String? = null
)
