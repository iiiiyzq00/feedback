package com.feedback.repository.query

import com.feedback.mapper.query.*
import com.feedback.model.po.query.*
import com.feedback.model.auth.DataAccessScope
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

data class MetricReconciliationCounts(
    var received: Long = 0, var privacyPass: Long = 0, var reviewNeeded: Long = 0,
    var failed: Long = 0, var pending: Long = 0, var aiProcessed: Long = 0,
    var aiAutoAccepted: Long = 0, var aiReviewPending: Long = 0, var aiReviewed: Long = 0,
    var aiOverruled: Long = 0, var clusterDecided: Long = 0, var linkedExisting: Long = 0,
    var newCluster: Long = 0
) {
    fun asMap(): Map<String, Any> = linkedMapOf(
        "received" to received, "privacyPass" to privacyPass, "reviewNeeded" to reviewNeeded,
        "failed" to failed, "pending" to pending, "aiProcessed" to aiProcessed,
        "aiAutoAccepted" to aiAutoAccepted, "aiReviewPending" to aiReviewPending,
        "aiReviewed" to aiReviewed, "aiOverruled" to aiOverruled, "clusterDecided" to clusterDecided,
        "linkedExisting" to linkedExisting, "newCluster" to newCluster)
}

@Repository
class MetricGovernanceRepository(
    private val definitions: MetricDefinitionMapper,
    private val sources: MetricSourceMapper,
    private val contents: MetricContentMapper,
    private val ai: MetricAiMapper,
    private val links: MetricClusterLinkMapper
) {
    fun definitions(): List<MetricDefinitionPo> = definitions.findActive(DEFINITION_SENTINEL).also {
        check(it.size < DEFINITION_SENTINEL) { "METRIC_DEFINITION_RESULT_TOO_LARGE" }
    }

    /** 每页只组装有限 sourceId；各关联表每页至多查询一次，避免 JOIN、N+1 和整库内存分页。 */
    fun reconciliation(scope: DataAccessScope, start: LocalDateTime): MetricReconciliationCounts {
        val counts = MetricReconciliationCounts()
        var afterId = 0L
        while (true) {
            val sourceIds = sources.findIdsAfter(start, scope.channels.ifEmpty { setOf("__NONE__") },
                scope.regions?.ifEmpty { setOf("__NONE__") }, afterId, BATCH)
            if (sourceIds.isEmpty()) break
            val contentRows = contents.findActive(sourceIds, scope.modules?.ifEmpty { setOf("__NONE__") })
            counts.received += contentRows.size
            contentRows.forEach { row -> when (row.maskingStatus) {
                "PASS" -> counts.privacyPass++
                "REVIEW_NEEDED" -> counts.reviewNeeded++
                "FAIL" -> counts.failed++
                "PENDING" -> counts.pending++
            } }
            val safeIds = contentRows.asSequence().filter { it.maskingStatus == "PASS" }.map { it.sourceId }.distinct().toList()
            if (safeIds.isNotEmpty()) {
                ai.findProcessed(safeIds).distinctBy { it.sourceId }.forEach { row ->
                    counts.aiProcessed++
                    when (row.reviewStatus) {
                        "NOT_REQUIRED" -> counts.aiAutoAccepted++
                        "PENDING" -> counts.aiReviewPending++
                        "CONFIRMED" -> counts.aiReviewed++
                        "CORRECTED" -> counts.aiOverruled++
                    }
                }
                links.findActiveMain(safeIds).groupBy { it.sourceId }.forEach { (_, sourceLinks) ->
                    counts.clusterDecided++
                    if (sourceLinks.any { it.linkType != "NEW_CLUSTER" }) counts.linkedExisting++
                    if (sourceLinks.any { it.linkType == "NEW_CLUSTER" }) counts.newCluster++
                }
            }
            afterId = sourceIds.last()
            if (sourceIds.size < BATCH) break
        }
        return counts
    }

    companion object {
        private const val BATCH = 500
        private const val DEFINITION_SENTINEL = 501
    }
}
