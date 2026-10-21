package com.feedback.repository.imports

import com.feedback.mapper.imports.*
import com.feedback.model.po.imports.*
import com.feedback.model.imports.*
import org.springframework.stereotype.Repository

data class ImportQualityFacts(
    val batch: ImportQualityBatchPo, val rows: ImportQualityRowCountsPo,
    val attachment: ImportQualityAttachmentCountsPo, val recordCount: Int,
    val appMapped: Int, val moduleMapped: Int, val versionMapped: Int, val crossDuplicates: Int
)

@Repository
class ImportQualityRepository(
    private val batches: ImportQualityBatchMapper,
    private val rows: ImportQualityRowMapper,
    private val sources: ImportQualitySourceMapper,
    private val contents: ImportQualityContentMapper,
    private val links: ImportQualityLinkMapper
) {
    fun facts(batchId: Long): ImportQualityFacts {
        val batch = batches.findBase(batchId) ?: importRepositoryNotFound("IMPORT_BATCH_NOT_FOUND")
        val sourceRows = sources.findByBatch(batchId)
        val sourceIds = sourceRows.map { it.id }
        val contentBySource = sourceIds.chunked(200).flatMap(contents::findBySourceIds).associateBy { it.sourceId }
        val representedSources = sourceRows.filter { it.id in contentBySource }
        val ownLinks = sourceIds.chunked(200).flatMap(links::findActiveBySourceIds)
        val clusterIds = ownLinks.map { it.clusterId }.distinct()
        val allLinks = clusterIds.chunked(200).flatMap(links::findActiveByClusterIds)
        val allSourceIds = allLinks.map { it.feedbackSourceId }.distinct()
        val channelBySource = allSourceIds.chunked(200).flatMap(sources::findByIds).associate { it.id to it.sourceChannel }
        val clustersBySource = ownLinks.groupBy { it.feedbackSourceId }
        val linksByCluster = allLinks.groupBy { it.clusterId }
        val crossDuplicates = representedSources.count { source ->
            clustersBySource[source.id].orEmpty().any { own ->
                linksByCluster[own.clusterId].orEmpty().any { other ->
                    channelBySource[other.feedbackSourceId]?.let { it != source.sourceChannel } == true
                }
            }
        }
        return ImportQualityFacts(
            batch, rows.counts(batchId), rows.attachmentCounts(batchId), representedSources.size,
            representedSources.count { !it.packageName.isNullOrBlank() },
            representedSources.count { !contentBySource[it.id]?.functionModule.isNullOrBlank() },
            representedSources.count { !it.appVersion.isNullOrBlank() }, crossDuplicates
        )
    }

    fun save(row: ImportQualityUpdatePo) { if(batches.updateSnapshot(row) != 1) importRepositoryFailed("IMPORT_QUALITY_SNAPSHOT_UPDATE_FAILED") }
    fun stored(batchId: Long) = batches.findStored(batchId)
}
