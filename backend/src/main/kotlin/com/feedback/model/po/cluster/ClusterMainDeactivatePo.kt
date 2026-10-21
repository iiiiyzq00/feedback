package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterMainDeactivatePo(
    val linkId: Long,
    val expectedVersion: Long,
    val reviewerId: Long,
    val reason: String,
    val requestId: String?,
    val decisionReason: String
)

