package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.ClusterShadowGenerationPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param

@Mapper
interface ClusterShadowGenerationMapper {
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insertBuilding(row: ClusterShadowGenerationPo): Int
    fun findByKey(@Param("generationKey") generationKey: String): ClusterShadowGenerationPo?
    fun findByKeyForUpdate(@Param("generationKey") generationKey: String): ClusterShadowGenerationPo?
    fun findBuildingForUpdate(): ClusterShadowGenerationPo?
    fun updateBuilt(row: ClusterShadowGenerationPo): Int
    fun markVerified(
        @Param("id") id: Long,
        @Param("actorId") actorId: Long,
        @Param("verificationDigest") verificationDigest: String
    ): Int
    fun markFailed(
        @Param("id") id: Long,
        @Param("failureCode") failureCode: String,
        @Param("failureSummary") failureSummary: String
    ): Int
}
