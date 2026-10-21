package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterAiDecisionCompletionPo(
    val id: Long,
    val holder: String,
    val outputSnapshot: String,
    val modelName: String?,
    val providerRequestId: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val latencyMs: Long?,
    val estimatedCostUsd: BigDecimal?
)

