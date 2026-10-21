package com.feedback.repository.knowledge

import com.feedback.mapper.knowledge.KnowledgeSearchMapper
import org.springframework.stereotype.Repository

@Repository
class KnowledgeSearchRepository(private val mapper: KnowledgeSearchMapper) {
    fun search(issue: String, module: String, limit: Int): List<Map<String, Any?>> =
        mapper.search(issue, module, limit).map { row -> linkedMapOf(
            "id" to row.id, "title" to row.title, "content" to row.content, "featureModule" to row.featureModule,
            "issueType" to row.issueType, "effectivenessScore" to row.effectivenessScore,
            "knowledgeSource" to row.knowledgeSource, "sourceRcaId" to row.sourceRcaId,
            "supersedesId" to row.supersedesId, "lifecycleVersion" to row.lifecycleVersion,
            "publishedAt" to row.publishedAt, "expiresAt" to row.expiresAt,
            "traceReference" to row.traceReference, "matchReason" to row.matchReason
        ) }
}
