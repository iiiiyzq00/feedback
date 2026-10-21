package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterDecisionFactPo(
    val factKey: String,
    val decisionType: String,
    val overturned: Boolean,
    val requestId: String,
    val candidateCount: Int,
    val sourceId: Long,
    val selectedClusterId: Long?,
    val reason: String,
    val reviewerId: Long,
    val snapshot: String
)

