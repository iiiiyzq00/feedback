package com.feedback.repository.imports

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.mapper.imports.*
import com.feedback.model.po.imports.*
import com.feedback.model.imports.*
import org.springframework.stereotype.Repository

@Repository
class ImportRollbackRepository(
    private val sources: ImportRollbackSourceMapper,
    private val links: ImportRollbackLinkMapper,
    private val contents: ImportRollbackContentMapper,
    private val clusters: ImportRollbackClusterMapper,
    private val json: ObjectMapper
) {
    fun rollback(batchId: Long) {
        val batchSources = sources.findByBatchId(batchId)
        if (batchSources.isEmpty()) return
        val sourceIds = batchSources.map { it.id }
        val affectedClusterIds = sourceIds.chunked(MAX_BATCH).flatMap(links::findActiveBySourceIds)
            .map { it.clusterId }.distinct().sorted()
        sourceIds.chunked(MAX_BATCH).forEach { chunk ->
            links.deactivateBySourceIds(chunk)
            contents.softDeleteBySourceIds(chunk)
        }
        if (affectedClusterIds.isEmpty()) return
        val remainingLinks = affectedClusterIds.chunked(MAX_BATCH).flatMap(links::findActiveMainByClusterIds)
        val remainingSourceIds = remainingLinks.map { it.feedbackSourceId }.distinct().sorted()
        val eligible = remainingSourceIds.chunked(MAX_BATCH).flatMap(contents::findEligibleSourceIds).toSet()
        val channelBySource = remainingSourceIds.chunked(MAX_BATCH).flatMap(sources::findByIds).associate { it.id to it.sourceChannel }
        val stats = affectedClusterIds.map { clusterId ->
            val active = remainingLinks.filter { it.clusterId == clusterId && it.feedbackSourceId in eligible }
            val distribution = active.groupingBy { channelBySource[it.feedbackSourceId] ?: "UNKNOWN" }.eachCount()
            ImportRollbackClusterStatsPo(clusterId, active.size.toLong(), json.writeValueAsString(distribution))
        }
        stats.chunked(MAX_BATCH).forEach { if(clusters.updateStatisticsBatch(it) != it.size) importRepositoryFailed("IMPORT_ROLLBACK_CLUSTER_UPDATE_FAILED") }
        val empty = stats.filter { it.total == 0L }.map { it.clusterId }
        empty.chunked(MAX_BATCH).forEach(clusters::archiveEmpty)
    }

    companion object { private const val MAX_BATCH = 200 }
}
