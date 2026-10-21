package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackDetailContentPo(
    var sourceId: Long = 0, var title: String? = null, var description: String? = null,
    var functionModule: String? = null, var usageStage: String? = null, var problemType: String? = null,
    var issueCategory: String? = null, var severityLevel: String? = null, var userSentiment: String? = null,
    var reproducibility: String? = null, var processingStatus: String = "OPEN", var maskingStatus: String = "",
    var mappingRuleVersion: String? = null, var normalizationVersion: String? = null,
    var dataQualityScore: BigDecimal? = null, var qualityRuleVersion: String? = null,
    var updatedAt: LocalDateTime? = null, var deleted: Boolean = false, var createdAt: LocalDateTime? = null
)

