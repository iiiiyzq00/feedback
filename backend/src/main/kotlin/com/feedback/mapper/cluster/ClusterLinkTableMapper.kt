package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper
interface ClusterLinkTableMapper {
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<ClusterLinkPo>
    fun lockBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<ClusterLinkPo>
    fun findActiveMainByClusterIdsAfter(@Param("clusterIds") clusterIds: List<Long>, @Param("afterLinkId") afterLinkId: Long, @Param("limit") limit: Int): List<ClusterLinkPo>
    fun lockActiveMainByClusterIds(@Param("clusterIds") clusterIds: List<Long>, @Param("limit") limit: Int): List<ClusterLinkPo>
    fun findPendingReviewSourcesAfter(
        @Param("afterPriority") afterPriority: String?,
        @Param("afterDueAt") afterDueAt: LocalDateTime?,
        @Param("afterSourceId") afterSourceId: Long,
        @Param("priority") priority: String?,
        @Param("limit") limit: Int
    ): List<ClusterReviewQueuePo>
    fun findCandidatesBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<ClusterLinkPo>
    fun findClusterIdsByProblemFingerprint(@Param("fingerprint") fingerprint: String, @Param("limit") limit: Int): List<Long>
    fun findRejectedClusterIds(@Param("sourceId") sourceId: Long): List<Long>
    fun findByReviewRequestId(@Param("reviewRequestId") reviewRequestId: String): ClusterLinkPo?
    fun insertAll(@Param("rows") rows: List<ClusterLinkInsertPo>): Int
    fun insertLegacy(@Param("row") row: ClusterLinkLegacyInsertPo): Int
    fun claimCandidates(@Param("rows") rows: List<ClusterCandidateClaimPo>): Int
    fun deactivateMainLinks(@Param("rows") rows: List<ClusterMainDeactivatePo>): Int
    fun skipCandidates(@Param("rows") rows: List<ClusterCandidateSkipPo>, @Param("reason") reason: String): Int
    fun updateProblemFingerprint(@Param("sourceId") sourceId: Long, @Param("clusterId") clusterId: Long, @Param("fingerprint") fingerprint: String): Int
    fun appendNameEvolution(@Param("sourceId") sourceId: Long, @Param("clusterId") clusterId: Long, @Param("from") from: String, @Param("to") to: String): Int
}

