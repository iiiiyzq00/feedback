package com.feedback.repository.problem.analysis

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.mapper.problem.analysis.ClusterFactsMapper
import com.feedback.mapper.problem.analysis.ClusterFeedbackProjectionMapper
import com.feedback.mapper.problem.analysis.ProblemAnalysisIdentityMapper
import com.feedback.mapper.problem.analysis.ProblemAnalysisMemberMapper
import com.feedback.model.po.problem.analysis.ClusterFactsPo
import com.feedback.model.po.problem.analysis.ClusterFeedbackProjectionPo
import com.feedback.model.po.problem.analysis.ProblemClusterPo
import com.feedback.model.po.problem.analysis.ProblemDimensionPo
import com.feedback.model.po.problem.analysis.ProblemIdentityCountPo
import com.feedback.model.po.feedback.listprojection.FeedbackClusterMemberProjectionPo
import com.feedback.model.query.problem.analysis.AnalysisCriteria
import org.springframework.stereotype.Repository

/**
 * 问题分析的原子事实查询边界；每个方法只访问一个 Mapper，并统一执行分页与 ID 批次上界校验。
 * 跨表快照组装由上层读取 Repository 负责，本类不解释业务可见性或生成 HTTP 模型。
 */
@Repository
class ProblemAnalysisFactsRepository(
    private val memberMapper: ProblemAnalysisMemberMapper,
    private val identityMapper: ProblemAnalysisIdentityMapper,
    private val clusterMapper: ClusterFactsMapper,
    private val projectionMapper: ClusterFeedbackProjectionMapper
) {
    fun member(generationId: Long, criteria: AnalysisCriteria, sourceId: Long) =
        memberMapper.findMemberBySourceId(generationId, criteria, sourceId)

    fun relatedCount(generationId: Long, criteria: AnalysisCriteria, clusterId: Long, excludedSourceId: Long) =
        memberMapper.countRelatedFeedback(generationId, criteria, clusterId, excludedSourceId)

    fun relatedPage(
        generationId: Long,
        criteria: AnalysisCriteria,
        clusterId: Long,
        excludedSourceId: Long,
        limit: Int,
        offset: Long
    ): List<FeedbackClusterMemberProjectionPo> {
        if (limit !in 1..50 || offset < 0) invalid("RELATED_FEEDBACK_PAGINATION_INVALID")
        return memberMapper.findRelatedFeedbackPage(generationId, criteria, clusterId, excludedSourceId, limit, offset)
    }

    fun totals(generationId: Long, criteria: AnalysisCriteria) = memberMapper.totals(generationId, criteria)

    fun page(
        generationId: Long,
        criteria: AnalysisCriteria,
        crossOnly: Boolean,
        limit: Int,
        offset: Long
    ): List<ProblemClusterPo> {
        if (limit !in 1..100 || offset < 0) invalid("PROBLEM_ANALYSIS_PAGINATION_INVALID")
        return memberMapper.findClusterPage(generationId, criteria, crossOnly, limit, offset)
    }

    fun summary(generationId: Long, criteria: AnalysisCriteria, clusterId: Long) =
        memberMapper.findClusterSummary(generationId, criteria, clusterId)

    fun identities(
        generationId: Long,
        criteria: AnalysisCriteria,
        clusterIds: List<Long>
    ): Map<Long, ProblemIdentityCountPo> {
        val ids = bounded(clusterIds)
        return if (ids.isEmpty()) emptyMap() else identityMapper.counts(generationId, criteria, ids)
            .associateBy { it.clusterId }
    }

    fun applications(generationId: Long, criteria: AnalysisCriteria, clusterIds: List<Long>) =
        dimensions(clusterIds) { memberMapper.findApplicationDimensions(generationId, criteria, it) }

    fun regions(generationId: Long, criteria: AnalysisCriteria, clusterIds: List<Long>) =
        dimensions(clusterIds) { memberMapper.findRegionDimensions(generationId, criteria, it) }

    fun representatives(generationId: Long, criteria: AnalysisCriteria, clusterIds: List<Long>): Map<Long, Long> {
        val ids = bounded(clusterIds)
        return if (ids.isEmpty()) emptyMap() else memberMapper.findRepresentatives(generationId, criteria, ids)
            .associate { it.clusterId to it.sourceId }
    }

    fun clusterFacts(clusterIds: List<Long>): Map<Long, ClusterFactsPo> {
        val ids = bounded(clusterIds)
        return if (ids.isEmpty()) emptyMap() else clusterMapper.findByIds(ids).associateBy { it.id }
    }

    fun feedbackCount(generationId: Long, criteria: AnalysisCriteria, clusterId: Long): Long =
        memberMapper.countClusterFeedback(generationId, criteria, clusterId)

    fun feedbackPage(
        generationId: Long,
        criteria: AnalysisCriteria,
        clusterId: Long,
        limit: Int,
        offset: Long
    ): List<FeedbackClusterMemberProjectionPo> {
        if (limit !in 1..100 || offset < 0) invalid("CLUSTER_FEEDBACK_PAGINATION_INVALID")
        return memberMapper.findClusterFeedbackPage(generationId, criteria, clusterId, limit, offset)
    }

    fun feedbackProjections(
        generationId: Long,
        sourceIds: List<Long>
    ): Map<Long, ClusterFeedbackProjectionPo> {
        val ids = bounded(sourceIds)
        return if (ids.isEmpty()) emptyMap() else projectionMapper.findByIds(generationId, ids)
            .associateBy { it.sourceId }
    }

    private fun dimensions(
        clusterIds: List<Long>,
        query: (List<Long>) -> List<ProblemDimensionPo>
    ) = bounded(clusterIds).let { ids -> if (ids.isEmpty()) emptyMap() else query(ids).groupBy { it.clusterId } }

    private fun bounded(ids: List<Long>): List<Long> = ids.distinct().also {
        if (it.size > 200 || it.any { id -> id <= 0 }) invalid("FEEDBACK_ANALYSIS_ID_BATCH_INVALID")
    }

    private fun invalid(reasonCode: String): Nothing = throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)
}
