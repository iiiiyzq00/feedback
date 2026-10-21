package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface ClusterReclassificationLinkMapper {
    fun findActiveSnapshotClusterIds(): List<Long>
    fun supersedeCandidates(@Param("runId") runId: Long): Int
    fun supersedeMains(@Param("runId") runId: Long): Int
    fun findActiveMainBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<ClusterLinkPo>
    fun findOwnedClusterIds(@Param("runId") runId: Long): List<Long>
    fun countUnownedActiveMains(@Param("clusterIds") clusterIds: List<Long>, @Param("runId") runId: Long): Long
    fun deactivateOwnedMains(@Param("clusterIds") clusterIds: List<Long>, @Param("runId") runId: Long): Int
    fun lockOwnedActiveMains(@Param("sourceIds") sourceIds: List<Long>, @Param("runId") runId: Long): List<ClusterLinkPo>
    fun deactivateIds(@Param("ids") ids: List<Long>): Int
    fun findActiveMainClusterIds(@Param("clusterIds") clusterIds: List<Long>): List<Long>
    fun countSupersededMains(@Param("runId") runId: Long): Long
    fun deactivateAllActiveMains(): Int
    fun restoreSupersededMains(@Param("runId") runId: Long): Int
    fun restoreSupersededCandidates(@Param("runId") runId: Long): Int
    fun findNewClusterIds(@Param("runId") runId: Long): List<Long>
    fun clearSuperseded(@Param("runId") runId: Long): Int
    fun findActiveMainAfter(@Param("afterId") afterId: Long, @Param("limit") limit: Int): List<ClusterLinkPo>
    fun counts(@Param("runId") runId: Long): ClusterReclassificationCountsPo
}

