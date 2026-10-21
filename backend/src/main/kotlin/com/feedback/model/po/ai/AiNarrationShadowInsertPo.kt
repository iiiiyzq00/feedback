package com.feedback.model.po.ai

import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AiNarrationShadowInsertPo(
    val skillCode: String, val promptVersion: String, val promptVersionId: Long, val taskStatus: String,
    val inputSnapshot: String, val outputSnapshot: String, val latencyMs: Long?, val inputTokens: Int?,
    val outputTokens: Int?, val estimatedCostUsd: BigDecimal?, val providerRequestId: String?,
    val skillVersion: String, val promptSha256: String, val errorDetail: String?
)

