package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterCandidateClaimPo(
    val sourceId: Long,
    val expectedVersion: Long,
    val selectedClusterId: Long?,
    val status: String,
    val reviewerId: Long,
    val reason: String,
    val requestId: String,
    val deactivate: Boolean
)

