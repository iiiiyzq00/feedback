package com.feedback.repository.report

import com.feedback.mapper.report.*
import com.feedback.model.po.report.*
import com.feedback.model.query.report.*
import org.springframework.stereotype.Repository

/** 问题簇报告单表读取边界；所有关联集合均按 500 个 ID 批量加载。 */
@Repository
class ClusterReportRepository(
    private val clusters: ClusterReportClusterMapper,
    private val links: ClusterReportLinkMapper,
    private val causeLinks: ClusterReportCauseLinkMapper,
    private val sources: ClusterReportSourceMapper,
    private val contents: ClusterReportContentMapper,
    private val identities: ClusterReportIdentityMapper,
    private val rcas: ClusterReportRcaMapper,
    private val knowledge: ClusterReportKnowledgeMapper,
    private val incidents: ClusterReportIncidentMapper,
) {
    private val batchSize = 500

    fun cluster(id: Long) = clusters.find(id)
    fun sourceLinks(id: Long) = links.byCluster(id)
    fun mainLinksForSource(id: Long) = links.bySource(id)
    fun causeLinks(id: Long) = causeLinks.byCluster(id)
    fun sources(ids: Collection<Long>) = ids.distinct().chunked(batchSize).flatMap(sources::byIds)
    fun contents(ids: Collection<Long>) = ids.distinct().chunked(batchSize).flatMap(contents::safeByIds)
    fun identities(ids: Collection<Long>) = ids.distinct().chunked(batchSize).flatMap(identities::byIds)
    fun rcas(ids: Collection<Long>) = ids.distinct().chunked(batchSize).flatMap(rcas::byIds)
    fun knowledge(ids: Collection<Long>) = ids.distinct().chunked(batchSize).flatMap(knowledge::byRcaIds)
    fun incidents(ids: Collection<Long>) = ids.distinct().chunked(batchSize).flatMap(incidents::byIds)
}
