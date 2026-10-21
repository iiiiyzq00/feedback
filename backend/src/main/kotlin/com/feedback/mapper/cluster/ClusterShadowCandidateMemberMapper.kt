package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.ClusterShadowCandidateMemberPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface ClusterShadowCandidateMemberMapper {
    fun insertAll(@Param("rows") rows: List<ClusterShadowCandidateMemberPo>): Int
    fun findByGeneration(@Param("generationId") generationId: Long): List<ClusterShadowCandidateMemberPo>
}
