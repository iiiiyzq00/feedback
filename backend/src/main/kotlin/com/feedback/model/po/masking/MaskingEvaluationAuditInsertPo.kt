package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class MaskingEvaluationAuditInsertPo(
    var id: Long = 0, val maskingRuleVersion: String, val reviewer: Long, val evaluationKey: String,
    val evalF1Score: BigDecimal, val nerF1Score: BigDecimal, val highRiskRecall: BigDecimal,
    val falseMaskingRate: BigDecimal, val samples: Int, val errorDetailsJson: String
)

