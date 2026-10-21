package com.feedback.model.po.ai

import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class GovernedSkillInvocationInsertPo(
    val skillCode: String, val taskKey: String, val sourceId: Long?, val promptVersion: String,
    val promptVersionId: Long?, val skillVersion: String, val promptSha256: String?, val modelConfig: String,
    val applicationVersion: String, val taskStatus: String, val inputSnapshot: String, val outputSnapshot: String,
    val errorDetail: String?, val latencyMs: Long?, val inputTokens: Int?, val outputTokens: Int?,
    val estimatedCostUsd: BigDecimal?, val providerRequestId: String?, val modelName: String?
)

