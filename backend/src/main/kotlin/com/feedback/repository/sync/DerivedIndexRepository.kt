package com.feedback.repository.sync

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.mapper.sync.*
import com.feedback.model.po.sync.*
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId

/**
 * 本次领取的索引任务。leaseHolder + leaseGeneration 是 claim 发出的 fencing token，
 * process 必须原样回传，complete/fail 才能证明「我写的还是我领的那一次」。
 */
data class ClaimedIndexTask(
    val id: Long, val inputSnapshot: String, val retryCount: Int,
    val leaseHolder: String, val leaseGeneration: Long
)

/** 索引任务写回结果；LEASE_LOST 表示本次写回被 lease_generation fencing 拒绝。 */
enum class IndexTaskWriteOutcome { APPLIED, LEASE_LOST }

data class FeedbackIndexFact(
    val sourceId: Long, val channel: String, val title: String?, val description: String?,
    val functionModule: String?, val category: String?, val severity: String?, val summary: String?
)

@Repository
class DerivedIndexRepository(
    private val tasks: IndexAiTaskMapper,
    private val ai: IndexFeedbackAiMapper,
    private val contents: IndexContentMapper,
    private val sources: IndexSourceMapper,
    private val clusters: IndexClusterMapper,
    private val json: ObjectMapper
) {
    fun seed(ruleVersion: String, vectorMode: String) {
        seedFeedback(ruleVersion, vectorMode)
        seedClusters(ruleVersion, vectorMode)
    }

    @Transactional
    fun claim(holder: String): ClaimedIndexTask? {
        val candidate = tasks.lockDue() ?: return null
        if (tasks.claim(candidate.id, holder) != 1) return null
        val claimed = tasks.findClaimed(candidate.id, holder) ?: return null
        // generation 由 claim 在数据库里自增，本事务内回读才能拿到本次领取真正生效的 fencing token。
        return ClaimedIndexTask(claimed.id, claimed.inputSnapshot, claimed.retryCount, holder, claimed.leaseGeneration)
    }

    /**
     * 写回影响行数为 0 不是故障：它说明本次 claim 的租约已经被别人接管
     * （A 领取 → 租约过期 → B 重新领取 → A 晚到写回），fencing 条件正是要拒绝这种旧结果，
     * 否则 A 的陈旧结果会覆盖 B 的状态。因此这里把 0 行当成 LEASE_LOST 正常返回，
     * 只有 >1 行（主键重复等不可能发生的情况）才按内部错误抛出。
     */
    fun complete(id: Long, holder: String, generation: Long): IndexTaskWriteOutcome =
        writeOutcome(tasks.complete(id, holder, generation), "INDEX_TASK_COMPLETE_AFFECTED_ROWS_INVALID")

    fun fail(id: Long, holder: String, generation: Long, retry: Int, terminal: Boolean, delay: Long,
             error: String): IndexTaskWriteOutcome =
        writeOutcome(tasks.fail(id, holder, generation, if (terminal) "FAILED" else "RETRY_PENDING",
            retry, terminal, delay, error), "INDEX_TASK_FAILURE_AFFECTED_ROWS_INVALID")

    private fun writeOutcome(affectedRows: Int, reasonCode: String): IndexTaskWriteOutcome = when (affectedRows) {
        1 -> IndexTaskWriteOutcome.APPLIED
        0 -> IndexTaskWriteOutcome.LEASE_LOST
        else -> throw ServiceException(ErrorCode.INTERNAL_ERROR, reasonCode)
    }

    fun feedback(id: Long): FeedbackIndexFact? {
        val source = sources.findById(id) ?: return null
        val content = contents.findById(id) ?: return null
        val aiFact = ai.findById(id) ?: return null
        if (content.maskingStatus != "PASS" || content.isDeleted) return null
        return FeedbackIndexFact(id, source.sourceChannel, content.title, content.description, content.functionModule,
            aiFact.aiCategory, aiFact.aiSeverity, aiFact.aiSummary)
    }

    fun cluster(id: Long): IndexClusterPo? = clusters.findById(id)
    fun markFeedbackSearch(id: Long, searchId: String) { affected(ai.markSearchSynced(id, searchId) in 0..1,"INDEX_FEEDBACK_SEARCH_UPDATE_INVALID") }
    fun markFeedbackVector(id: Long, vectorId: String, model: String, rule: String, dimension: Int) {
        affected(ai.markVectorSynced(id, vectorId, model, rule, dimension) in 0..1,"INDEX_FEEDBACK_VECTOR_UPDATE_INVALID")
    }
    fun markFeedbackVectorUnavailable(id: Long, rule: String) { affected(ai.markVectorUnavailable(id, rule) in 0..1,"INDEX_FEEDBACK_VECTOR_UNAVAILABLE_UPDATE_INVALID") }
    fun markFeedbackDeleted(id: Long, vectorStatus: String) { affected(ai.markDeleted(id, vectorStatus) in 0..1,"INDEX_FEEDBACK_DELETE_UPDATE_INVALID") }
    fun markFeedbackPending(id: Long) { affected(ai.markPending(id) in 0..1,"INDEX_FEEDBACK_PENDING_UPDATE_INVALID") }
    fun markClusterSearch(id: Long) { affected(clusters.markSearchSynced(id) in 0..1,"INDEX_CLUSTER_SEARCH_UPDATE_INVALID") }
    fun markClusterVector(id: Long, rule: String) { affected(clusters.markVectorSynced(id, rule) in 0..1,"INDEX_CLUSTER_VECTOR_UPDATE_INVALID") }
    fun markClusterVectorUnavailable(id: Long, rule: String) { affected(clusters.markVectorUnavailable(id, rule) in 0..1,"INDEX_CLUSTER_VECTOR_UNAVAILABLE_UPDATE_INVALID") }
    fun markClusterDeleted(id: Long, vectorStatus: String) { affected(clusters.markDeleted(id, vectorStatus) in 0..1,"INDEX_CLUSTER_DELETE_UPDATE_INVALID") }

    private fun seedFeedback(ruleVersion: String, vectorMode: String) {
        val missing = mutableListOf<IndexOutboxInsertPo>()
        var after = 0L
        while (missing.size < SEED_LIMIT) {
            val page = ai.findAfter(after, PAGE)
            if (page.isEmpty()) break
            val contentById = contents.findByIds(page.map { it.sourceId }).associateBy { it.sourceId }
            val pageCandidates = page.mapNotNull { row ->
                val content = contentById[row.sourceId] ?: return@mapNotNull null
                val needsTask = content.maskingStatus != "PASS" || content.isDeleted ||
                    row.searchIndexStatus != "SYNCED" || row.vectorIndexStatus != "SYNCED" ||
                    row.indexMaskingRuleVersion.orEmpty() != ruleVersion
                if (!needsTask) return@mapNotNull null
                val changedAt = maxOf(content.updatedAt, row.aiProcessedAt ?: content.updatedAt)
                val key = "INDEX:FEEDBACK:${row.sourceId}:${epochMillis(changedAt)}:$ruleVersion:$vectorMode"
                val operation = if (content.maskingStatus == "PASS" && !content.isDeleted) "UPSERT" else "DELETE"
                IndexOutboxInsertPo(row.sourceId, key, json.writeValueAsString(mapOf(
                    "entityType" to "FEEDBACK", "entityId" to row.sourceId,
                    "operation" to operation, "vectorMode" to vectorMode
                )))
            }
            missing += withoutExisting(pageCandidates).take(SEED_LIMIT - missing.size)
            after = page.last().sourceId
            if (page.size < PAGE) break
        }
        insertMissing(missing)
    }

    private fun seedClusters(ruleVersion: String, vectorMode: String) {
        val missing = mutableListOf<IndexOutboxInsertPo>()
        var after = 0L
        while (missing.size < SEED_LIMIT) {
            val page = clusters.findAfter(after, PAGE)
            if (page.isEmpty()) break
            val pageCandidates = page.mapNotNull { row ->
                val needsTask = row.searchIndexStatus.orEmpty().ifBlank { "PENDING" } != "SYNCED" ||
                    row.vectorIndexStatus.orEmpty().ifBlank { "PENDING" } != "SYNCED" ||
                    row.indexMaskingRuleVersion.orEmpty() != ruleVersion
                if (!needsTask) return@mapNotNull null
                val key = "INDEX:CLUSTER:${row.id}:${epochMillis(row.updatedAt)}:$ruleVersion:$vectorMode"
                val operation = if (row.clusterStatus in DELETED_CLUSTER_STATES) "DELETE" else "UPSERT"
                IndexOutboxInsertPo(null, key, json.writeValueAsString(mapOf(
                    "entityType" to "CLUSTER", "entityId" to row.id,
                    "operation" to operation, "vectorMode" to vectorMode
                )))
            }
            missing += withoutExisting(pageCandidates).take(SEED_LIMIT - missing.size)
            after = page.last().id
            if (page.size < PAGE) break
        }
        insertMissing(missing)
    }

    private fun withoutExisting(rows: List<IndexOutboxInsertPo>): List<IndexOutboxInsertPo> {
        if (rows.isEmpty()) return emptyList()
        val existing = tasks.findTaskKeys(rows.map { it.taskKey }).toSet()
        return rows.filterNot { it.taskKey in existing }
    }

    private fun insertMissing(rows: List<IndexOutboxInsertPo>) {
        rows.chunked(PAGE).forEach { chunk ->
            val existing = tasks.findTaskKeys(chunk.map { it.taskKey }).toSet()
            val missing = chunk.filterNot { it.taskKey in existing }
            if (missing.isNotEmpty()) {
                val affected = tasks.insertOutbox(missing)
                affected(affected in 0..missing.size,"INDEX_OUTBOX_INSERT_AFFECTED_ROWS_INVALID")
            }
        }
    }

    private fun epochMillis(value: java.time.LocalDateTime): Long =
        value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun affected(condition:Boolean,reasonCode:String) {
        if(!condition) throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
    }

    companion object {
        const val PAGE = 200
        const val SEED_LIMIT = 500
        val DELETED_CLUSTER_STATES = setOf("MERGED", "SPLIT_ARCHIVED", "ARCHIVED")
    }
}
