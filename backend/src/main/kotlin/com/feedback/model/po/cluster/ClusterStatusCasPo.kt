package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterStatusCasPo(
    val id: Long,
    val expectedVersion: Long,
    val targetStatus: String,
    val operatorId: Long?,
    val reason: String,
    val mergedIntoClusterId: Long? = null,
    val clearStatistics: Boolean = false
)

