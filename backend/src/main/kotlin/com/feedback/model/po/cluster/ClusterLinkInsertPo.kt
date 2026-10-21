package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterLinkInsertPo(
    var id: Long? = null,
    val clusterId: Long,
    val feedbackSourceId: Long,
    val relationRole: String,
    val linkType: String,
    val matchSimilarity: BigDecimal? = null,
    val candidateRank: Int? = null,
    val matchEvidence: String? = null,
    val decisionVersion: String? = null,
    val decisionConfigVersion: String? = null,
    val featureSnapshot: String? = null,
    val problemFingerprint: String? = null,
    val reviewStatus: String,
    val reviewVersion: Long = 0,
    val reviewedBy: Long? = null,
    val reviewReason: String? = null,
    val reviewRequestId: String? = null,
    val decisionReason: String? = null,
    val reclassificationRunId: Long? = null
)

