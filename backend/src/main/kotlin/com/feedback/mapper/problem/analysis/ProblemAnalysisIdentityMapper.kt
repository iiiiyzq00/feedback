package com.feedback.mapper.problem.analysis

import com.feedback.model.po.problem.analysis.ProblemIdentityCountPo
import com.feedback.model.query.problem.analysis.AnalysisCriteria
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 不可逆身份投影单表 Mapper；仅返回按当前范围聚合的去重计数。 */
@Mapper
interface ProblemAnalysisIdentityMapper {
    fun counts(
        @Param("generationId") generationId: Long, @Param("criteria") criteria: AnalysisCriteria,
        @Param("clusterIds") clusterIds: List<Long>
    ): List<ProblemIdentityCountPo>
}
