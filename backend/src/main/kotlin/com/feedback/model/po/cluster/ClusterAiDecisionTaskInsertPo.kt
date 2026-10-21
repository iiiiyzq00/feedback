package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterAiDecisionTaskInsertPo(
    val sourceId: Long,
    val taskKey: String,
    val promptVersion: String,
    val skillVersion: String,
    val promptSha256: String,
    val inputSnapshot: String,
    val maxRetries: Int
)

