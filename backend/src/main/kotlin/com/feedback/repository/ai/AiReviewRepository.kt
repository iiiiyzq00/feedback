package com.feedback.repository.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.mapper.ai.*
import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import com.feedback.model.auth.DataAccessScope
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

data class AiReviewFact(
    val sourceId: Long, val sourceChannel: String, val title: String?, val aiSummary: String?,
    val aiCategory: String?, val aiSeverity: String?, val confidence: BigDecimal?, val explanation: String?,
    val evidenceCodes: String?, val currentCategory: String?, val currentSeverity: String?,
    val processedAt: LocalDateTime?, val reviewReason: String
)

@Repository
class AiReviewRepository(
    private val sources: AiReviewSourceMapper,
    private val contents: AiReviewContentMapper,
    private val ai: AiReviewAiMapper,
    private val audits: AiReviewAuditMapper,
    private val json: ObjectMapper
) {
    fun pending(scope: DataAccessScope, threshold: BigDecimal): List<AiReviewFact> {
        if (scope.channels.isEmpty() || scope.modules?.isEmpty() == true || scope.regions?.isEmpty() == true) return emptyList()
        val result = mutableListOf<AiReviewFact>(); var after = 0L
        while (true) {
            val sourceRows = sources.findScopedAfter(after, scope.channels, scope.regions, BATCH)
            if (sourceRows.isEmpty()) break
            val sourceById = sourceRows.associateBy { it.id }
            val contentRows = contents.findSafe(sourceRows.map { it.id }, scope.modules)
            val contentById = contentRows.associateBy { it.sourceId }
            val pendingRows = if (contentRows.isEmpty()) emptyList() else ai.findPending(contentRows.map { it.sourceId })
            pendingRows.forEach { row ->
                val source = sourceById.getValue(row.sourceId); val content = contentById.getValue(row.sourceId)
                result += AiReviewFact(row.sourceId, source.sourceChannel,
                    content.title?.takeIf(String::isNotBlank) ?: content.description?.take(100), row.aiSummary,
                    row.aiCategory, row.aiSeverity, row.aiConfidence, row.aiExplanation, row.evidenceCodes,
                    content.issueCategory, content.severityLevel, row.aiProcessedAt, reason(row, threshold))
            }
            after = sourceRows.last().id
            if (sourceRows.size < BATCH) break
        }
        return result
    }

    fun requireAccessible(sourceId: Long, scope: DataAccessScope) {
        val source = sources.findByIds(listOf(sourceId)).singleOrNull()
        val content = contents.findByIds(listOf(sourceId)).singleOrNull()
        if (source == null || content == null || !scope.permits(source.sourceChannel, content.functionModule,
                source.provinceCode, source.cityCode)) throw com.feedback.common.exception.ClientException(com.feedback.common.ErrorCode.NOT_FOUND,"AI_REVIEW_NOT_FOUND")
    }

    fun requireAccessible(ids: List<Long>, scope: DataAccessScope) {
        if (scope.channels.isEmpty()) throw com.feedback.common.exception.ClientException(com.feedback.common.ErrorCode.AUTH_FORBIDDEN,"AI_REVIEW_BATCH_ACCESS_DENIED")
        val sourceById = sources.findByIds(ids).associateBy { it.id }
        val contentById = contents.findSafe(ids, scope.modules).associateBy { it.sourceId }
        val accessible = ids.filter { id ->
            val source = sourceById[id]; val content = contentById[id]
            source != null && content != null && scope.permits(source.sourceChannel, content.functionModule,
                source.provinceCode, source.cityCode)
        }.toSet()
        ensureAiRepositoryInput(accessible == ids.toSet()) { "AI_REVIEW_BATCH_ACCESS_DENIED" }
    }

    fun applyReview(sourceId: Long, action: String, category: String?, severity: String?, reviewer: Long,
                    comment: String) {
        if (action == "OVERRULE") contents.overrule(sourceId, aiRepositoryRequired(category), aiRepositoryRequired(severity), reviewer)
        else {
            val row = ai.findPending(listOf(sourceId)).singleOrNull()
                ?: throw com.feedback.common.exception.ClientException(com.feedback.common.ErrorCode.BAD_REQUEST,"AI_REVIEW_RESULT_INCOMPLETE")
            val aiCategory=aiRepositoryRequired(row.aiCategory) { "AI_REVIEW_RESULT_INCOMPLETE" }
            val aiSeverity=aiRepositoryRequired(row.aiSeverity) { "AI_REVIEW_RESULT_INCOMPLETE" }
            ensureAiRepositoryState(contents.adoptBatch(listOf(AiReviewAdoptionPo(sourceId,
                aiCategory, aiSeverity)), reviewer) == 1)
        }
        if (ai.complete(sourceId, if (action == "CONFIRM") "CONFIRMED" else "CORRECTED", reviewer, comment) == 0)
            throw com.feedback.common.exception.ClientException(com.feedback.common.ErrorCode.BAD_REQUEST,"AI_REVIEW_NOT_PENDING")
        ensureAiRepositoryState(audits.insertOne(reviewer, sourceId, action, category, severity) == 1)
    }

    fun applyBatch(ids: List<Long>, action: String, category: String?, severity: String?, reviewer: Long,
                   comment: String): Int {
        if (action == "OVERRULE") contents.overruleBatch(ids, aiRepositoryRequired(category), aiRepositoryRequired(severity), reviewer)
        else {
            val rows = ai.findPending(ids)
            ensureAiRepositoryInput(rows.size == ids.size && rows.all { it.aiCategory != null && it.aiSeverity != null }) { "AI_REVIEW_BATCH_RESULT_INCOMPLETE" }
            val adopted = rows.map { AiReviewAdoptionPo(it.sourceId, aiRepositoryRequired(it.aiCategory), aiRepositoryRequired(it.aiSeverity)) }
            ensureAiRepositoryInput(contents.adoptBatch(adopted, reviewer) == ids.size) { "AI_REVIEW_BATCH_RESULT_INCOMPLETE" }
        }
        val changed = ai.completeBatch(ids, if (action == "CONFIRM") "CONFIRMED" else "CORRECTED", reviewer, comment)
        ensureAiRepositoryInput(changed == ids.size) { "AI_REVIEW_BATCH_NOT_ALL_PENDING" }
        ensureAiRepositoryState(audits.insertBatch(reviewer, "batch-${UUID.randomUUID()}", action, category, severity, ids.size,
            json.writeValueAsString(ids)) == 1)
        return changed
    }

    private fun reason(row: AiReviewAiPo, threshold: BigDecimal) = when {
        row.aiSeverity in setOf("S0", "S1") && row.aiConfidence != null && row.aiConfidence!! < threshold -> "BOTH"
        row.aiSeverity in setOf("S0", "S1") -> "HIGH_RISK"
        row.aiConfidence != null && row.aiConfidence!! < threshold -> "LOW_CONFIDENCE"
        else -> "LEGACY_PENDING"
    }
    companion object { private const val BATCH = 500 }
}
