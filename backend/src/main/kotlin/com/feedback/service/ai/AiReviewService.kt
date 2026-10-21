package com.feedback.service.ai

import com.feedback.config.RuntimeConfigService
import com.feedback.repository.ai.AiReviewFact
import com.feedback.repository.ai.AiReviewRepository
import com.feedback.service.auth.DataPermissionService
import com.feedback.service.masking.MaskingPipeline
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class AiReviewRequest(
    val action: String,
    val category: String? = null,
    val severity: String? = null,
    val comment: String
)

data class AiBatchReviewRequest(
    val sourceIds: List<Long>,
    val action: String,
    val category: String? = null,
    val severity: String? = null,
    val comment: String
)

@Service
class AiReviewService(
    private val repository: AiReviewRepository,
    private val permissions: DataPermissionService,
    private val masking: MaskingPipeline,
    private val runtimeConfig: RuntimeConfigService? = null
) {
    fun pending(page: Int, size: Int, reason: String? = null): Map<String, Any?> {
        ensureAi(page >= 1 && size in 1..100) { "AI_REVIEW_PAGE_INVALID" }
        val normalizedReason = reason?.takeIf(String::isNotBlank)?.uppercase()
        ensureAi(normalizedReason == null || normalizedReason in REASONS) { "AI_REVIEW_REASON_INVALID" }
        val threshold = runtimeConfig?.decimal(REVIEW_THRESHOLD_KEY, DEFAULT_REVIEW_THRESHOLD) ?: DEFAULT_REVIEW_THRESHOLD
        val all = repository.pending(permissions.scope(), threshold)
        val selected = all.filter { fact -> matchesReason(fact, normalizedReason) }
            .sortedWith(compareBy<AiReviewFact>({ REASON_ORDER.indexOf(it.reviewReason) },
                { it.confidence }, { it.processedAt }))
        val records = selected.drop((page - 1) * size).take(size).map(::record)
        val high = setOf("S0", "S1")
        val summary = linkedMapOf<String, Any?>(
            "total" to all.size.toLong(),
            "both" to all.count { it.aiSeverity in high && it.confidence != null && it.confidence < threshold }.toLong(),
            "highRiskOnly" to all.count { it.aiSeverity in high && it.confidence != null && it.confidence >= threshold }.toLong(),
            "lowConfidenceOnly" to all.count { it.aiSeverity !in high && it.confidence != null && it.confidence < threshold }.toLong(),
            "legacyPending" to all.count { it.aiSeverity !in high && (it.confidence == null || it.confidence >= threshold) }.toLong(),
            "oldestProcessedAt" to all.mapNotNull(AiReviewFact::processedAt).minOrNull()
        )
        return mapOf("records" to records, "total" to selected.size.toLong(), "page" to page, "size" to size,
            "summary" to summary, "reason" to normalizedReason, "threshold" to threshold,
            "queueDefinition" to "置信度低于${threshold.stripTrailingZeros().toPlainString()}，或严重度为S0/S1的已生成AI结果",
            "contractVersion" to "ai-review-queue-v2")
    }

    @Transactional
    fun review(sourceId: Long, request: AiReviewRequest, reviewerId: Long) {
        ensureAi(request.comment.trim().length in 2..1000) { "AI_REVIEW_COMMENT_REQUIRED" }
        val masked = masking.mask(request.comment.trim(), "ai_review_$sourceId")
        ensureAi(masked.maskingStatus == "PASS") { "AI_REVIEW_COMMENT_MASKING_FAILED" }
        val action = validateAction(request.action)
        val category = request.category?.uppercase(); val severity = request.severity?.uppercase()
        if (action == "OVERRULE") validateDecision(category, severity)
        repository.requireAccessible(sourceId, permissions.scope())
        repository.applyReview(sourceId, action, category, severity, reviewerId, masked.maskedText)
    }

    @Transactional
    fun reviewBatch(request: AiBatchReviewRequest, reviewerId: Long): Int {
        val ids = request.sourceIds.distinct()
        ensureAi(ids.isNotEmpty() && ids.size <= 500) { "AI_REVIEW_BATCH_SIZE_INVALID" }
        ensureAi(request.comment.trim().length in 2..1000) { "AI_REVIEW_COMMENT_REQUIRED" }
        val masked = masking.mask(request.comment.trim(), "ai_review_batch")
        ensureAi(masked.maskingStatus == "PASS") { "AI_REVIEW_COMMENT_MASKING_FAILED" }
        val action = validateAction(request.action)
        val category = request.category?.uppercase(); val severity = request.severity?.uppercase()
        if (action == "OVERRULE") validateDecision(category, severity)
        repository.requireAccessible(ids, permissions.scope())
        return repository.applyBatch(ids, action, category, severity, reviewerId, masked.maskedText)
    }

    private fun validateAction(value: String) = value.uppercase().also {
        ensureAi(it in setOf("CONFIRM", "OVERRULE")) { "AI_REVIEW_ACTION_INVALID" }
    }
    private fun validateDecision(category: String?, severity: String?) {
        ensureAi(category != null) { "AI_REVIEW_CATEGORY_REQUIRED" }
        ensureAi(severity != null) { "AI_REVIEW_SEVERITY_REQUIRED" }
        ensureAi(category in AiClassificationTaxonomy.categoryCodes && severity in AiClassificationTaxonomy.severityCodes) {
            "AI_REVIEW_ENUM_INVALID"
        }
    }
    private fun matchesReason(fact: AiReviewFact, reason: String?) = when (reason) {
        null -> true
        "BOTH" -> fact.reviewReason == "BOTH"
        "HIGH_RISK" -> fact.reviewReason in setOf("BOTH", "HIGH_RISK")
        "LOW_CONFIDENCE" -> fact.reviewReason in setOf("BOTH", "LOW_CONFIDENCE")
        else -> fact.reviewReason == "LEGACY_PENDING"
    }
    private fun record(f: AiReviewFact) = linkedMapOf<String, Any?>(
        "sourceId" to f.sourceId, "sourceChannel" to f.sourceChannel, "title" to f.title,
        "aiSummary" to f.aiSummary, "aiCategory" to f.aiCategory, "aiSeverity" to f.aiSeverity,
        "confidence" to f.confidence, "explanation" to f.explanation, "evidenceCodes" to f.evidenceCodes,
        "currentCategory" to f.currentCategory, "currentSeverity" to f.currentSeverity,
        "processedAt" to f.processedAt, "reviewReason" to f.reviewReason
    )

    companion object {
        const val REVIEW_THRESHOLD_KEY = "ai.review-confidence-threshold"
        val DEFAULT_REVIEW_THRESHOLD: BigDecimal = BigDecimal("0.850000")
        private val REASONS = setOf("BOTH", "HIGH_RISK", "LOW_CONFIDENCE", "LEGACY_PENDING")
        private val REASON_ORDER = listOf("BOTH", "HIGH_RISK", "LOW_CONFIDENCE", "LEGACY_PENDING")
    }
}
