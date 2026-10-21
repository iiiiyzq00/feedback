package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterLinkPo(
    var id: Long = 0,
    var clusterId: Long = 0,
    var feedbackSourceId: Long = 0,
    var relationRole: String = "MAIN",
    var linkType: String = "NEW_CLUSTER",
    var matchSimilarity: BigDecimal? = null,
    var candidateRank: Int? = null,
    var matchEvidence: String? = null,
    var decisionVersion: String? = null,
    var decisionConfigVersion: String? = null,
    var featureSnapshot: String? = null,
    var problemFingerprint: String? = null,
    var reviewStatus: String = "NOT_REQUIRED",
    var reviewPriority: String? = null,
    var reviewDueAt: LocalDateTime? = null,
    var reviewVersion: Long = 0,
    var reviewedBy: Long? = null,
    var reviewedAt: LocalDateTime? = null,
    var reviewReason: String? = null,
    var reviewRequestId: String? = null,
    var decisionReason: String? = null,
    var reclassificationRunId: Long? = null,
    var supersededByReclassificationRunId: Long? = null,
    var isActive: Boolean = true,
    var createdAt: LocalDateTime = LocalDateTime.MIN
)

