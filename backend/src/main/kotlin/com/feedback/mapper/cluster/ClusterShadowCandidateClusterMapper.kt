package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.ClusterShadowCandidateClusterPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface ClusterShadowCandidateClusterMapper {
    fun insertAll(@Param("rows") rows: List<ClusterShadowCandidateClusterPo>): Int
    fun findByGeneration(@Param("generationId") generationId: Long): List<ClusterShadowCandidateClusterPo>
}
