package com.feedback.model.po.ai

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AiReviewAiPo(
    var sourceId: Long = 0, var aiSummary: String? = null, var aiCategory: String? = null,
    var aiSeverity: String? = null, var aiConfidence: BigDecimal? = null, var aiExplanation: String? = null,
    var evidenceCodes: String? = null, var aiProcessedAt: LocalDateTime? = null
)

