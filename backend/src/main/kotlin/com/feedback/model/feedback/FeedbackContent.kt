package com.feedback.model.feedback

import java.math.BigDecimal
import java.time.LocalDateTime

/** 脱敏正文与平台处理状态；持久化由 CoreFeedbackContentMapper 负责。 */
data class FeedbackContent(
    val sourceId: Long = 0,
    val title: String? = null,
    val description: String? = null,
    val functionModule: String? = null,
    val problemType: String? = null,
    val feedbackTags: String? = null,
    val usageStage: String? = null,
    val severityLevel: String? = null,
    val issueCategory: String? = null,
    val userSentiment: String? = null,
    val reproducibility: String? = null,
    val mappingError: String? = null,
    val mappingRuleVersion: String? = null,
    val normalizationVersion: String? = null,
    val processingStatus: String = "OPEN",
    val maskingStatus: String = "PENDING",
    val dataQualityScore: BigDecimal? = null,
    val qualityRuleVersion: String? = null,
    val isDeleted: Boolean = false,
    val createdBy: Long? = null,
    val updatedBy: Long? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
