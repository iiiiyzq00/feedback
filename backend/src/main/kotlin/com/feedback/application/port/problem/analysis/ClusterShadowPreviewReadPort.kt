package com.feedback.application.port.problem.analysis

import com.feedback.model.po.cluster.ClusterShadowCandidateClusterPo
import com.feedback.model.po.cluster.ClusterShadowCandidateMemberPo
import com.feedback.model.po.cluster.ClusterShadowGenerationPo

data class ClusterShadowSafeProblemEvidence(
    val sourceId: Long,
    val channel: String,
    val applicationKey: String?,
    val applicationName: String?,
    val title: String?,
    val description: String?,
    val functionModule: String?,
    val issueCategory: String?
)

data class ClusterShadowPreviewDataset(
    val generation: ClusterShadowGenerationPo,
    val clusters: List<ClusterShadowCandidateClusterPo>,
    val members: List<ClusterShadowCandidateMemberPo>,
    val evidenceBySourceId: Map<Long, ClusterShadowSafeProblemEvidence>,
    val terminalCountByStatus: Map<String, Long>
)

fun interface ClusterShadowPreviewReadPort {
    fun readVerified(generationKey: String): ClusterShadowPreviewDataset
}
