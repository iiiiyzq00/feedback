package com.feedback.model.po.cluster

data class ClusterShadowCandidateClusterPo(
    val generationId: Long,
    val clusterKeyHash: String,
    val clusterNameHash: String,
    val memberCount: Long,
    val provisionalMemberCount: Long,
    val manualMemberCount: Long
)
