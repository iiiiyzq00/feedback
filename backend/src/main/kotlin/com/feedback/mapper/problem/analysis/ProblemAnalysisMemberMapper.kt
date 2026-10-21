package com.feedback.mapper.problem.analysis

import com.feedback.model.po.problem.analysis.ClusterRepresentativePo
import com.feedback.model.po.problem.analysis.ProblemClusterPo
import com.feedback.model.po.problem.analysis.ProblemDimensionPo
import com.feedback.model.po.problem.analysis.ProblemTotalsPo
import com.feedback.model.po.feedback.listprojection.FeedbackClusterMemberProjectionPo
import com.feedback.model.query.problem.analysis.AnalysisCriteria
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 成员投影单表 Mapper：负责主分页、同表聚合、维度和代表行，不读取其他业务表。 */
@Mapper
interface ProblemAnalysisMemberMapper {
    fun findMemberBySourceId(
        @Param("generationId") generationId: Long, @Param("criteria") criteria: AnalysisCriteria,
        @Param("sourceId") sourceId: Long
    ): FeedbackClusterMemberProjectionPo?
    fun countRelatedFeedback(
        @Param("generationId") generationId: Long, @Param("criteria") criteria: AnalysisCriteria,
        @Param("clusterId") clusterId: Long, @Param("excludedSourceId") excludedSourceId: Long
    ): Long
    fun findRelatedFeedbackPage(
        @Param("generationId") generationId: Long, @Param("criteria") criteria: AnalysisCriteria,
        @Param("clusterId") clusterId: Long, @Param("excludedSourceId") excludedSourceId: Long,
        @Param("limit") limit: Int, @Param("offset") offset: Long
    ): List<FeedbackClusterMemberProjectionPo>
    fun totals(@Param("generationId") generationId: Long, @Param("criteria") criteria: AnalysisCriteria): ProblemTotalsPo
    fun findClusterPage(
        @Param("generationId") generationId: Long, @Param("criteria") criteria: AnalysisCriteria,
        @Param("crossChannelOnly") crossChannelOnly: Boolean, @Param("limit") limit: Int, @Param("offset") offset: Long
    ): List<ProblemClusterPo>
    fun findClusterSummary(
        @Param("generationId") generationId: Long, @Param("criteria") criteria: AnalysisCriteria,
        @Param("clusterId") clusterId: Long
    ): ProblemClusterPo?
    fun countClusterFeedback(
        @Param("generationId") generationId: Long, @Param("criteria") criteria: AnalysisCriteria,
        @Param("clusterId") clusterId: Long
    ): Long
    fun findClusterFeedbackPage(
        @Param("generationId") generationId: Long, @Param("criteria") criteria: AnalysisCriteria,
        @Param("clusterId") clusterId: Long, @Param("limit") limit: Int, @Param("offset") offset: Long
    ): List<FeedbackClusterMemberProjectionPo>
    fun findApplicationDimensions(
        @Param("generationId") generationId: Long, @Param("criteria") criteria: AnalysisCriteria,
        @Param("clusterIds") clusterIds: List<Long>
    ): List<ProblemDimensionPo>
    fun findRegionDimensions(
        @Param("generationId") generationId: Long, @Param("criteria") criteria: AnalysisCriteria,
        @Param("clusterIds") clusterIds: List<Long>
    ): List<ProblemDimensionPo>
    fun findRepresentatives(
        @Param("generationId") generationId: Long, @Param("criteria") criteria: AnalysisCriteria,
        @Param("clusterIds") clusterIds: List<Long>
    ): List<ClusterRepresentativePo>
}
