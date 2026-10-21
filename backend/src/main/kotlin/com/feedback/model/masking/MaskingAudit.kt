package com.feedback.model.masking

import java.math.BigDecimal
import java.time.LocalDateTime

/** 脱敏评估和规则变更审计事实；只记录受控指标、版本与审核结论。 */
data class MaskingAudit(
    var id: Long = 0,
    val auditType: String,
    val maskingRuleVersion: String,
    val reviewConclusion: String? = null,
    val reviewer: Long? = null,
    val evaluationKey: String? = null,
    val evalPrecision: BigDecimal? = null,
    val evalRecall: BigDecimal? = null,
    val evalF1Score: BigDecimal? = null,
    val evalPassedSamples: Int? = null,
    val evalTotalSamples: Int? = null,
    val evalErrorDetails: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
