package com.feedback.model.cluster

import java.math.BigDecimal
import java.time.LocalDateTime

/** 反馈与问题簇的领域关联；活动 MAIN 关系受唯一约束与版本规则保护。 */
data class ClusterLink(
    val id: Long = 0,
    val clusterId: Long = 0,
    val feedbackSourceId: Long = 0,
    val relationRole: String = "MAIN",
    val linkType: String = "NEW_CLUSTER",
    val matchSimilarity: BigDecimal? = null,
    val candidateRank: Int? = null,
    val matchEvidence: String? = null,
    val decisionVersion: String? = null,
    val decisionConfigVersion: String? = null,
    val featureSnapshot: String? = null,
    val problemFingerprint: String? = null,
    val reviewStatus: String = "NOT_REQUIRED",
    val reviewPriority: String? = null,
    val reviewDueAt: LocalDateTime? = null,
    val reviewVersion: Long = 0,
    val reviewedBy: Long? = null,
    val reviewedAt: LocalDateTime? = null,
    val reviewReason: String? = null,
    val reviewRequestId: String? = null,
    val decisionReason: String? = null,
    val reclassificationRunId: Long? = null,
    val supersededByReclassificationRunId: Long? = null,
    val isActive: Boolean = true,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
