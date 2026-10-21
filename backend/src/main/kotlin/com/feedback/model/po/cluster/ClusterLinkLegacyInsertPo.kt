package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterLinkLegacyInsertPo(
    var id: Long? = null,
    val clusterId: Long,
    val feedbackSourceId: Long,
    val relationRole: String,
    val linkType: String,
    val matchSimilarity: BigDecimal?,
    val candidateRank: Int?,
    val matchEvidence: String?,
    val decisionVersion: String?,
    val decisionConfigVersion: String?,
    val featureSnapshot: String?,
    val problemFingerprint: String?,
    val reviewStatus: String,
    val reviewPriority: String?,
    val reviewDueAt: LocalDateTime?,
    val reviewVersion: Long,
    val reviewedBy: Long?,
    val reviewedAt: LocalDateTime?,
    val reviewReason: String?,
    val reviewRequestId: String?,
    val decisionReason: String?,
    val reclassificationRunId: Long?,
    val supersededByReclassificationRunId: Long?,
    val isActive: Boolean
)

