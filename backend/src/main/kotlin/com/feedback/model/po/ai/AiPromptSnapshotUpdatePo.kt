package com.feedback.model.po.ai

import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AiPromptSnapshotUpdatePo(
    val id: Long,
    val promptVersionId: Long,
    val skillVersion: String,
    val promptSha256: String,
    val modelConfig: String,
    val applicationVersion: String?,
    val latencyMs: Long?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val estimatedCostUsd: BigDecimal?,
    val providerRequestId: String?,
    val modelName: String
)

