package com.feedback.service.cluster

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.cluster.ClusterStatisticsUpdatePo
import com.feedback.repository.cluster.ClusterMutationRepository
import com.feedback.repository.cluster.ClusterProfileFactRepository
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

@Service
class ClusterStatisticsService(
    private val repository: ClusterMutationRepository,
    private val profileFacts: ClusterProfileFactRepository,
    private val json: ObjectMapper,
    private val meters: MeterRegistry,
    private val businessAudits: BusinessAuditService,
    private val transactions: TransactionOperations,
) {
    /**
     * active MAIN 是唯一统计事实。Cluster 按 ID 升序锁定，link/source 各按 200 条批量读取，
     * Mapper 返回数或更新数异常立即抛出，让调用方的 cluster/link/audit/dirty 一起回滚。
     */
    @Transactional
    fun recompute(clusterIds: Collection<Long>): Int = recomputeInCurrentTransaction(clusterIds)

    private fun recomputeInCurrentTransaction(clusterIds: Collection<Long>): Int {
        val ids = clusterIds.distinct().sorted()
        if (ids.isEmpty()) return 0
        ensureClusterRequest(ids.size <= ClusterMutationRepository.MAX_CLUSTER_BATCH) { "CLUSTER_STATISTICS_BATCH_TOO_LARGE" }
        val locked = repository.lockClusters(ids)
        ensureClusterInvariant(locked.map { it.id } == ids) { "CLUSTER_STATISTICS_LOCK_SET_MISMATCH" }
        val totals = ids.associateWith { 0 }.toMutableMap()
        val channels = ids.associateWith { sortedMapOf<String, Int>() }.toMutableMap()
        repository.visitActiveMainPages(ids) { page ->
            val facts = profileFacts.accessFacts(page.map { it.feedbackSourceId })
            ensureClusterInvariant(facts.size == page.size, ErrorCode.FEEDBACK_READ_UNAVAILABLE) { "CLUSTER_STATISTICS_SOURCE_FACT_MISSING" }
            page.forEach { link ->
                val channel = facts.getValue(link.feedbackSourceId).sourceChannel
                totals[link.clusterId] = totals.getValue(link.clusterId) + 1
                channels.getValue(link.clusterId).merge(channel, 1, Int::plus)
            }
        }
        repository.updateStatistics(ids.map { id ->
            ClusterStatisticsUpdatePo(id, totals.getValue(id), json.writeValueAsString(channels.getValue(id)))
        })
        return ids.size
    }

    @Transactional
    fun recomputeOne(clusterId: Long): Int = recomputeInCurrentTransaction(listOf(clusterId))

    /** 全量维护使用 keyset 小批次；不使用 JOIN，也不把全部 link/source 载入内存。 */
    fun recomputeAll(): Int {
        var after = 0L
        var changed = 0
        while (true) {
            val ids = repository.clusterIdsAfter(after, RECONCILE_PAGE_SIZE)
            if (ids.isEmpty()) break
            changed += transactions.execute { recomputeInCurrentTransaction(ids) }
                ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "CLUSTER_STATISTICS_TRANSACTION_RESULT_MISSING")
            after = ids.last()
            if (ids.size < RECONCILE_PAGE_SIZE) break
        }
        return changed
    }

    data class Drift(
        val clusterId: Long,
        val cachedCount: Int,
        val actualCount: Int,
        val cachedChannels: String,
        val actualChannels: String
    )

    fun findDrift(limit: Int = 500): List<Drift> {
        ensureClusterRequest(limit in 1..5000) { "CLUSTER_DRIFT_LIMIT_INVALID" }
        val result = mutableListOf<Drift>()
        var after = 0L
        while (result.size < limit) {
            val ids = repository.clusterIdsAfter(after, RECONCILE_PAGE_SIZE)
            if (ids.isEmpty()) break
            val cached = repository.clusters(ids).associateBy { it.id }
            val totals = ids.associateWith { 0 }.toMutableMap()
            val channels = ids.associateWith { sortedMapOf<String, Int>() }.toMutableMap()
            repository.visitActiveMainPages(ids) { page ->
                val facts = profileFacts.accessFacts(page.map { it.feedbackSourceId })
                ensureClusterInvariant(facts.size == page.size, ErrorCode.FEEDBACK_READ_UNAVAILABLE) { "CLUSTER_DRIFT_SOURCE_FACT_MISSING" }
                page.forEach { link ->
                    val channel = facts.getValue(link.feedbackSourceId).sourceChannel
                    totals[link.clusterId] = totals.getValue(link.clusterId) + 1
                    channels.getValue(link.clusterId).merge(channel, 1, Int::plus)
                }
            }
            ids.forEach { id ->
                val cluster = cached.getValue(id)
                val actualJson = json.writeValueAsString(channels.getValue(id))
                val cachedJson = canonicalJson(cluster.channelDistribution)
                if (cluster.totalRecordCount != totals.getValue(id) || cachedJson != canonicalJson(actualJson)) {
                    result += Drift(id, cluster.totalRecordCount, totals.getValue(id), cachedJson, actualJson)
                }
            }
            after = ids.last()
            if (ids.size < RECONCILE_PAGE_SIZE) break
        }
        return result.take(limit)
    }

    fun reconcile() {
        val drift = findDrift()
        if (drift.isEmpty()) return
        drift.map { it.clusterId }.chunked(ClusterMutationRepository.MAX_CLUSTER_BATCH).forEach { ids ->
            transactions.executeWithoutResult { recomputeInCurrentTransaction(ids) }
        }
        meters.counter("feedback_cluster_statistics_drift_total").increment(drift.size.toDouble())
        businessAudits.append(BusinessAuditEvent(
            null, "CLUSTER_STATISTICS_RECONCILE", "SYSTEM", "SUCCESS", "CLUSTER_STATISTICS", null,
            UUID.randomUUID().toString(), null, mapOf(
                "driftCount" to drift.size,
                "clusterIdSample" to drift.take(100).map { it.clusterId },
                "clusterIdSampleTruncated" to (drift.size > 100),
                "repairMode" to "RECOMPUTE_FROM_ACTIVE_MAIN"
            )
        ))
    }

    private fun canonicalJson(value: String?): String = json.writeValueAsString(
        value?.takeIf(String::isNotBlank)?.let(json::readTree) ?: json.createObjectNode()
    )

    companion object { private const val RECONCILE_PAGE_SIZE = 200 }
}
