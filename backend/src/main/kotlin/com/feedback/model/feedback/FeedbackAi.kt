package com.feedback.model.feedback

import java.math.BigDecimal
import java.time.LocalDateTime

/** 反馈 AI 派生结果；模型输出与人工复核状态共同描述当前可信快照。 */
data class FeedbackAi(
    val sourceId: Long = 0,
    val aiSummary: String? = null,
    val aiApplication: String? = null,
    val aiFunctionModule: String? = null,
    val aiCategory: String? = null,
    val aiSentiment: String? = null,
    val aiRiskType: String? = null,
    val aiSeverity: String? = null,
    val aiConfidence: BigDecimal? = null,
    val aiExplanation: String? = null,
    val recommendedTeam: String? = null,
    val evidenceCodes: String? = null,
    val dataSources: String? = null,
    val aiProcessedAt: LocalDateTime? = null,
    val aiModelVersion: String? = null,
    val classificationPromptVersion: String? = null,
    val classificationModelVersion: String? = null,
    val classificationTaxonomyVersion: String = "ai-taxonomy-v1",
    val reviewStatus: String = "PENDING",
    val reviewComment: String? = null,
    val textFingerprint: Long? = null,
    val milvusVectorId: String? = null,
    val vectorIndexStatus: String? = null,
    val searchDocumentId: String? = null,
    val searchIndexStatus: String? = null,
    val embeddingModelVersion: String? = null,
    val embeddingDimension: Short? = null,
    val embeddingGeneratedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
