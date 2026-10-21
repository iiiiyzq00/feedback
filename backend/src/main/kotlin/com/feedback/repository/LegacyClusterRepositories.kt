package com.feedback.repository

import com.feedback.model.cluster.Cluster
import com.feedback.model.cluster.ClusterLink
import com.feedback.mapper.cluster.*
import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.springframework.stereotype.Repository
import java.util.Optional

/** 兼容历史测试与外部调用方法名的单表 MyBatis Cluster Repository。 */
@Repository
class ClusterRepository(private val mapper: ClusterTableMapper) {
    fun save(row: Cluster): Cluster {
        require(row.id == 0L) { "CLUSTER_LEGACY_UPDATE_NOT_SUPPORTED" }
        val insert = ClusterLegacyInsertPo(
            publicId = row.publicId, clusterName = row.clusterName, clusterNameKey = row.clusterNameKey,
            productScope = row.productScope, functionModule = row.functionModule, issueCategory = row.issueCategory,
            canonicalSymptom = row.canonicalSymptom, canonicalObject = row.canonicalObject,
            canonicalTrigger = row.canonicalTrigger, errorSignature = row.errorSignature,
            totalRecordCount = row.totalRecordCount, channelDistribution = row.channelDistribution ?: "{}",
            clusterStatus = row.clusterStatus, lifecycleVersion = row.lifecycleVersion,
            statisticsVersion = row.statisticsVersion, mergedIntoClusterId = row.mergedIntoClusterId,
            splitFromClusterId = row.splitFromClusterId, statusChangedBy = row.statusChangedBy,
            statusChangedAt = row.statusChangedAt, statusReason = row.statusReason,
            vectorIndexStatus = row.vectorIndexStatus, searchIndexStatus = row.searchIndexStatus
        )
        check(mapper.insertLegacy(insert) == 1) { "CLUSTER_INSERT_FAILED" }
        return row.copy(id = requireNotNull(insert.id))
    }

    fun findById(id: Long): Optional<Cluster> = Optional.ofNullable(mapper.findByIds(listOf(id)).singleOrNull()?.toDomain())

    private fun ClusterPo.toDomain() = Cluster(
        id, publicId, clusterName, clusterNameKey, productScope, functionModule, issueCategory,
        canonicalSymptom, canonicalObject, canonicalTrigger, errorSignature, totalRecordCount,
        channelDistribution, clusterStatus, lifecycleVersion, statisticsVersion, statisticsReconciledAt,
        mergedIntoClusterId, splitFromClusterId, statusChangedBy, statusChangedAt, statusReason,
        vectorIndexStatus, searchIndexStatus, createdAt, updatedAt
    )
}

/** 兼容历史测试方法名的单表 MyBatis ClusterLink Repository。 */
@Repository
class ClusterLinkRepository(private val mapper: ClusterLinkTableMapper) {
    fun save(row: ClusterLink): ClusterLink {
        require(row.id == 0L) { "CLUSTER_LINK_LEGACY_UPDATE_NOT_SUPPORTED" }
        val insert = ClusterLinkLegacyInsertPo(
            clusterId = row.clusterId, feedbackSourceId = row.feedbackSourceId,
            relationRole = row.relationRole, linkType = row.linkType, matchSimilarity = row.matchSimilarity,
            candidateRank = row.candidateRank, matchEvidence = row.matchEvidence,
            decisionVersion = row.decisionVersion, decisionConfigVersion = row.decisionConfigVersion,
            featureSnapshot = row.featureSnapshot, problemFingerprint = row.problemFingerprint,
            reviewStatus = row.reviewStatus, reviewPriority = row.reviewPriority, reviewDueAt = row.reviewDueAt,
            reviewVersion = row.reviewVersion, reviewedBy = row.reviewedBy, reviewedAt = row.reviewedAt,
            reviewReason = row.reviewReason, reviewRequestId = row.reviewRequestId,
            decisionReason = row.decisionReason, reclassificationRunId = row.reclassificationRunId,
            supersededByReclassificationRunId = row.supersededByReclassificationRunId, isActive = row.isActive
        )
        check(mapper.insertLegacy(insert) == 1) { "CLUSTER_LINK_INSERT_FAILED" }
        return row.copy(id = requireNotNull(insert.id))
    }

    fun saveAndFlush(row: ClusterLink): ClusterLink = save(row)
    fun findByFeedbackSourceIdAndRelationRoleAndIsActiveTrue(sourceId: Long, relationRole: String): ClusterLink? =
        active(sourceId, relationRole).singleOrNull()
    fun findAllByFeedbackSourceIdAndRelationRoleAndIsActiveTrueOrderByCandidateRankAsc(
        sourceId: Long, relationRole: String
    ): List<ClusterLink> = active(sourceId, relationRole).sortedWith(compareBy<ClusterLink> { it.candidateRank }.thenBy { it.id })
    fun findAllByFeedbackSourceIdInAndRelationRoleAndIsActiveTrue(
        sourceIds: Collection<Long>, relationRole: String
    ): List<ClusterLink> = if (sourceIds.isEmpty()) emptyList() else mapper.findBySourceIds(sourceIds.distinct().sorted())
        .filter { it.isActive && it.relationRole == relationRole }.map { it.toDomain() }
    fun findAllByFeedbackSourceIdAndReviewStatus(sourceId: Long, reviewStatus: String): List<ClusterLink> =
        mapper.findBySourceIds(listOf(sourceId)).filter { it.reviewStatus == reviewStatus }.map { it.toDomain() }
    fun findByReviewRequestId(reviewRequestId: String): ClusterLink? =
        mapper.findByReviewRequestId(reviewRequestId)?.toDomain()

    private fun active(sourceId: Long, role: String) = mapper.findBySourceIds(listOf(sourceId))
        .filter { it.isActive && it.relationRole == role }.map { it.toDomain() }

    private fun ClusterLinkPo.toDomain() = ClusterLink(
        id, clusterId, feedbackSourceId, relationRole, linkType, matchSimilarity, candidateRank,
        matchEvidence, decisionVersion, decisionConfigVersion, featureSnapshot, problemFingerprint,
        reviewStatus, reviewPriority, reviewDueAt, reviewVersion, reviewedBy, reviewedAt, reviewReason,
        reviewRequestId, decisionReason, reclassificationRunId, supersededByReclassificationRunId,
        isActive, createdAt
    )
}
