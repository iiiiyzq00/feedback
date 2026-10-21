package com.feedback.repository.cluster

import com.feedback.mapper.cluster.*
import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * 全量重分类的持久化协调边界。
 *
 * Mapper 始终只访问一张表；需要判断“安全正文是否已有 MAIN”、旧簇是否仍有成员等关系时，
 * 在这里按固定批次读取各表并用类型化集合组装，避免 JOIN、跨表子查询和逐行查询。
 */
@Repository
class ClusterReclassificationRepository(
    private val runs: ClusterReclassificationRunMapper,
    private val snapshots: ClusterReclassificationSnapshotMapper,
    private val contents: ClusterReclassificationContentMapper,
    private val links: ClusterReclassificationLinkMapper,
    private val clusters: ClusterReclassificationClusterMapper,
    private val clusterTable: ClusterTableMapper
) {
    fun run(id: Long): ClusterReclassificationRunPo? = runs.findById(id)
    fun lockRun(id: Long): ClusterReclassificationRunPo? = runs.lockById(id)
    fun byRequestId(requestId: String): ClusterReclassificationRunPo? = runs.findByRequestId(requestId)
    fun latest(): ClusterReclassificationRunPo? = runs.findLatest()
    fun hasActive(excludeId: Long? = null): Boolean = runs.lockAnyActive(excludeId) != null
    fun safeCount(): Long = contents.countSafe()

    fun create(publicId: ByteArray, requestId: String, reason: String, userId: Long, safeCount: Long): Long {
        val row = ClusterReclassificationRunPo(
            publicId = publicId, requestId = requestId, reason = reason,
            requestedBy = userId, snapshotSafeCount = safeCount
        )
        ensureClusterRepositoryInvariant(runs.insert(row) == 1) { "CLUSTER_RECLASSIFICATION_INSERT_FAILED" }
        return row.id ?: clusterRepositoryInvariantViolation("CLUSTER_RECLASSIFICATION_ID_MISSING")
    }

    fun snapshotAndIsolate(runId: Long, userId: Long) {
        val clusterIds = links.findActiveSnapshotClusterIds().distinct().sorted()
        clusterIds.chunked(BATCH).forEach { chunk ->
            val clusterRows = clusterTable.findByIds(chunk)
            ensureClusterRepositoryInvariant(clusterRows.map { it.id }.toSet() == chunk.toSet()) { "CLUSTER_RECLASSIFICATION_SNAPSHOT_CLUSTER_MISSING" }
            val rows = clusterRows.map { cluster ->
                ClusterReclassificationSnapshotPo(
                    runId, cluster.id, cluster.clusterStatus, cluster.statusReason,
                    cluster.statusChangedBy, cluster.statusChangedAt, cluster.lifecycleVersion
                )
            }
            ensureClusterRepositoryInvariant(snapshots.insertAll(rows) == rows.size) { "CLUSTER_RECLASSIFICATION_SNAPSHOT_INSERT_MISMATCH" }
        }
        links.supersedeCandidates(runId)
        links.supersedeMains(runId)
        archive(clusterIds, "全量重分类期间隔离旧候选", userId, clearStatistics = false,
            onlyOperational = false, updateActor = true)
    }

    fun nextUnlinkedSafeSources(limit: Int): List<Long> {
        ensureClusterRepositoryRequest(limit in 1..BATCH) { "CLUSTER_RECLASSIFICATION_BATCH_INVALID" }
        var createdAt: LocalDateTime? = null
        var sourceId = 0L
        val result = mutableListOf<Long>()
        while (result.size < limit) {
            val page = contents.findSafeAfter(createdAt, sourceId, BATCH)
            ensureClusterRepositoryInvariant(page.size <= BATCH) { "CLUSTER_RECLASSIFICATION_CONTENT_PAGE_OVERFLOW" }
            if (page.isEmpty()) break
            val ids = page.map { it.sourceId }
            ensureClusterRepositoryInvariant(ids.distinct().size == ids.size) { "CLUSTER_RECLASSIFICATION_CONTENT_DUPLICATED" }
            val linked = links.findActiveMainBySourceIds(ids).map { it.feedbackSourceId }.toSet()
            result += ids.filterNot(linked::contains).take(limit - result.size)
            val tail = page.last()
            createdAt = tail.createdAt
            sourceId = tail.sourceId
            if (page.size < BATCH) break
        }
        return result
    }

    fun claim(holder: String): Long? {
        val id = runs.claimable(holder) ?: return null
        ensureClusterRepositoryInvariant(runs.claim(id, holder) == 1) { "CLUSTER_RECLASSIFICATION_LEASE_LOST" }
        return id
    }

    fun renew(id: Long, holder: String) {
        ensureClusterRepositoryInvariant(runs.renew(id, holder) == 1) { "CLUSTER_RECLASSIFICATION_LEASE_LOST" }
    }

    fun beginFinalizing(id: Long, holder: String) {
        ensureClusterRepositoryInvariant(runs.beginFinalizing(id, holder) == 1) { "CLUSTER_RECLASSIFICATION_LEASE_LOST" }
    }

    fun complete(id: Long, holder: String) {
        ensureClusterRepositoryInvariant(runs.complete(id, holder) == 1) { "CLUSTER_RECLASSIFICATION_LEASE_LOST" }
    }

    fun beginRollback(id: Long, holder: String) {
        ensureClusterRepositoryInvariant(runs.beginRollback(id, holder) == 1) { "CLUSTER_RECLASSIFICATION_ROLLBACK_STATE_CONFLICT" }
    }

    fun completeRollback(id: Long) {
        ensureClusterRepositoryInvariant(runs.completeRollback(id) == 1) { "CLUSTER_RECLASSIFICATION_ROLLBACK_STATE_CONFLICT" }
    }

    fun invalidOwnedClusters(runId: Long): List<Long> {
        val owned = links.findOwnedClusterIds(runId).distinct().sorted()
        return owned.chunked(BATCH).flatMap(clusters::findInvalidOperationalByIds).distinct().sorted()
    }

    fun countUnownedActiveMains(clusterIds: Collection<Long>, runId: Long): Long =
        clusterIds.distinct().chunked(BATCH).sumOf { links.countUnownedActiveMains(it, runId) }

    fun deactivateOwnedMains(clusterIds: Collection<Long>, runId: Long): Int =
        clusterIds.distinct().chunked(BATCH).sumOf { links.deactivateOwnedMains(it, runId) }

    fun ownedActiveMains(sourceIds: Collection<Long>, runId: Long): List<ClusterLinkPo> =
        sourceIds.distinct().chunked(BATCH).flatMap { links.lockOwnedActiveMains(it, runId) }

    fun deactivateLinks(ids: Collection<Long>): Int =
        ids.distinct().chunked(BATCH).sumOf(links::deactivateIds)

    fun archiveWithoutActiveMain(clusterIds: Collection<Long>, reason: String, userId: Long?, clearStatistics: Boolean): Int {
        val ids = clusterIds.distinct().sorted()
        val active = ids.chunked(BATCH).flatMap(links::findActiveMainClusterIds).toSet()
        return archive(ids.filterNot(active::contains), reason, userId, clearStatistics,
            onlyOperational = true, updateActor = true)
    }

    fun archiveEveryOperationalWithoutActiveMain(reason: String, userId: Long?, clearStatistics: Boolean): Int {
        var after = 0L
        var affected = 0
        while (true) {
            val page = clusters.findOperationalAfter(after, BATCH)
            if (page.isEmpty()) break
            affected += archiveWithoutActiveMain(page.map { it.id }, reason, userId, clearStatistics)
            after = page.last().id
            if (page.size < BATCH) break
        }
        return affected
    }

    fun rollbackLinks(runId: Long) {
        val expected = links.countSupersededMains(runId)
        links.deactivateAllActiveMains()
        val restored = links.restoreSupersededMains(runId)
        ensureClusterRepositoryInvariant(restored.toLong() == expected) { "CLUSTER_RECLASSIFICATION_ROLLBACK_MAIN_COUNT_MISMATCH" }
        links.restoreSupersededCandidates(runId)
    }

    fun restoreSnapshots(runId: Long) {
        snapshots.findByRunId(runId).chunked(BATCH).forEach { rows ->
            val affected = clusters.restoreAll(rows)
            ensureClusterRepositoryInvariant(affected == rows.size) { "CLUSTER_RECLASSIFICATION_SNAPSHOT_RESTORE_MISMATCH" }
        }
    }

    fun archiveNewClustersWithoutActiveMain(runId: Long, reason: String): Int =
        archive(
            links.findNewClusterIds(runId).let { ids ->
                val active = ids.chunked(BATCH).flatMap(links::findActiveMainClusterIds).toSet()
                ids.filterNot(active::contains)
            },
            reason, null, clearStatistics = false, onlyOperational = false, updateActor = false
        )

    fun clearSuperseded(runId: Long) { links.clearSuperseded(runId) }

    fun coverage(runId: Long): ClusterReclassificationCoverage {
        val safe = mutableSetOf<Long>()
        var createdAt: LocalDateTime? = null
        var sourceId = 0L
        while (true) {
            val page = contents.findSafeAfter(createdAt, sourceId, BATCH)
            if (page.isEmpty()) break
            safe += page.map { it.sourceId }
            val tail = page.last()
            createdAt = tail.createdAt
            sourceId = tail.sourceId
            if (page.size < BATCH) break
        }
        val activeSafeRows = mutableListOf<ClusterLinkPo>()
        var afterLinkId = 0L
        while (true) {
            val page = links.findActiveMainAfter(afterLinkId, BATCH)
            activeSafeRows += page.filter { it.feedbackSourceId in safe }
            if (page.isEmpty() || page.size < BATCH) break
            afterLinkId = page.last().id
        }
        return ClusterReclassificationCoverage(
            safe = safe.size.toLong(),
            mains = activeSafeRows.map { it.feedbackSourceId }.distinct().size.toLong(),
            mainRows = activeSafeRows.size.toLong(),
            runMains = activeSafeRows.filter { it.reclassificationRunId == runId }.map { it.feedbackSourceId }.distinct().size.toLong(),
            unownedSafeMains = activeSafeRows.filter { it.reclassificationRunId != runId }.map { it.feedbackSourceId }.distinct().size.toLong()
        )
    }

    fun duplicateOperationalNames(): Long = clusters.countDuplicateOperationalNames()
    fun invalidOperationalNames(): Long = clusters.countInvalidOperationalNames()

    fun updateSnapshotSafeCount(id: Long, safe: Long) {
        ensureClusterRepositoryInvariant(runs.updateSnapshotSafeCount(id, safe) == 1) { "CLUSTER_RECLASSIFICATION_RUN_MISSING" }
    }

    fun refreshCounts(id: Long) {
        ensureClusterRepositoryInvariant(runs.updateCounts(id, links.counts(id)) == 1) { "CLUSTER_RECLASSIFICATION_RUN_MISSING" }
    }

    fun resumeFailed(id: Long) {
        ensureClusterRepositoryInvariant(runs.resumeFailed(id) == 1) { "CLUSTER_RECLASSIFICATION_NAME_QUALITY_REPAIR_STATE_CONFLICT" }
    }

    fun resumeFailedOrCompleted(id: Long) {
        ensureClusterRepositoryInvariant(runs.resumeFailedOrCompleted(id) == 1) { "CLUSTER_RECLASSIFICATION_MEMBER_REPAIR_STATE_CONFLICT" }
    }

    fun fail(id: Long, holder: String?, detail: String) {
        ensureClusterRepositoryInvariant(runs.fail(id, holder, detail) == 1) { "CLUSTER_RECLASSIFICATION_FAILURE_STATE_CONFLICT" }
    }

    private fun archive(
        ids: Collection<Long>,
        reason: String,
        userId: Long?,
        clearStatistics: Boolean,
        onlyOperational: Boolean,
        updateActor: Boolean
    ): Int =
        ids.distinct().chunked(BATCH).sumOf { chunk ->
            clusters.archiveAll(chunk.map {
                ClusterReclassificationArchivePo(it, reason, userId, clearStatistics, onlyOperational, updateActor)
            })
        }

    companion object { const val BATCH = 200 }
}

data class ClusterReclassificationCoverage(
    val safe: Long,
    val mains: Long,
    val mainRows: Long,
    val runMains: Long,
    val unownedSafeMains: Long
)
