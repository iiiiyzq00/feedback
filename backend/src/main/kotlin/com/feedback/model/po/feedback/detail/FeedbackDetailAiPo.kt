package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackDetailAiPo(
    var sourceId: Long = 0, var aiSummary: String? = null, var aiApplication: String? = null,
    var aiFunctionModule: String? = null, var aiCategory: String? = null, var aiSentiment: String? = null,
    var aiRiskType: String? = null, var recommendedTeam: String? = null, var dataSources: String? = null,
    var aiSeverity: String? = null, var aiConfidence: BigDecimal? = null, var aiExplanation: String? = null,
    var aiModelVersion: String? = null, var aiProcessedAt: LocalDateTime? = null,
    var classificationPromptVersion: String? = null, var reviewStatus: String? = null
)

