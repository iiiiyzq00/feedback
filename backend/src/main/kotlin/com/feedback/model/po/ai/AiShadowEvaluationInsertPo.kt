package com.feedback.model.po.ai

import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AiShadowEvaluationInsertPo(
    val promptVersion: String,
    val promptVersionId: Long,
    val sourceId: Long,
    val inputSnapshot: String,
    val outputSnapshot: String,
    val latencyMs: Long?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val estimatedCostUsd: BigDecimal?,
    val providerRequestId: String?,
    val skillVersion: String,
    val promptSha256: String
)

