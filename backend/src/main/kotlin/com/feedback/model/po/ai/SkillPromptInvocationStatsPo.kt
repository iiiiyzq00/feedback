package com.feedback.model.po.ai

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class SkillPromptInvocationStatsPo(
    var promptVersion: String = "",
    var invocationCount: Long = 0,
    var successCount: Long = 0,
    var failureCount: Long = 0,
    var avgLatencyMs: BigDecimal? = null,
    var p50LatencyMs: Long? = null,
    var p95LatencyMs: Long? = null,
    var p99LatencyMs: Long? = null,
    var inputTokens: Long? = null,
    var outputTokens: Long? = null,
    var estimatedCostUsd: BigDecimal? = null,
)

