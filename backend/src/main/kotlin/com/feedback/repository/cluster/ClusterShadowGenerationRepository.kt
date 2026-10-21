package com.feedback.repository.cluster

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.cluster.ClusterShadowCandidateClusterMapper
import com.feedback.mapper.cluster.ClusterShadowCandidateMemberMapper
import com.feedback.mapper.cluster.ClusterShadowGenerationMapper
import com.feedback.model.po.cluster.ClusterShadowCandidateClusterPo
import com.feedback.model.po.cluster.ClusterShadowCandidateMemberPo
import com.feedback.model.po.cluster.ClusterShadowGenerationPo
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 只管理 shadow candidate 三张表；没有 Cluster、Link 或 ACTIVE generation 依赖。 */
@Repository
class ClusterShadowGenerationRepository(
    private val generations: ClusterShadowGenerationMapper,
    private val clusters: ClusterShadowCandidateClusterMapper,
    private val members: ClusterShadowCandidateMemberMapper
) {
    @Transactional
    fun createBuilding(row: ClusterShadowGenerationPo): ClusterShadowGenerationPo {
        invariant(generations.findBuildingForUpdate() == null, "CLUSTER_SHADOW_BUILDING_ALREADY_EXISTS")
        invariant(generations.findByKeyForUpdate(row.generationKey) == null, "CLUSTER_SHADOW_GENERATION_KEY_EXISTS")
        invariant(generations.insertBuilding(row) == 1 && row.id > 0, "CLUSTER_SHADOW_GENERATION_INSERT_FAILED")
        return row
    }

    fun findByKey(key: String): ClusterShadowGenerationPo? = generations.findByKey(key)

    @Transactional
    fun persistBuild(
        generation: ClusterShadowGenerationPo,
        clusterRows: List<ClusterShadowCandidateClusterPo>,
        memberRows: List<ClusterShadowCandidateMemberPo>
    ) {
        memberRows.chunked(BATCH).forEach { rows ->
            invariant(members.insertAll(rows) == rows.size, "CLUSTER_SHADOW_MEMBER_INSERT_MISMATCH")
        }
        clusterRows.chunked(BATCH).forEach { rows ->
            invariant(clusters.insertAll(rows) == rows.size, "CLUSTER_SHADOW_CLUSTER_INSERT_MISMATCH")
        }
        invariant(generations.updateBuilt(generation) == 1, "CLUSTER_SHADOW_BUILD_UPDATE_CONFLICT")
    }

    fun members(generationId: Long): List<ClusterShadowCandidateMemberPo> = members.findByGeneration(generationId)
    fun clusters(generationId: Long): List<ClusterShadowCandidateClusterPo> = clusters.findByGeneration(generationId)

    @Transactional
    fun markVerified(key: String, actorId: Long, digest: String): ClusterShadowGenerationPo {
        val row = generations.findByKeyForUpdate(key)
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "CLUSTER_SHADOW_GENERATION_NOT_FOUND")
        invariant(row.generationStatus == "BUILDING", "CLUSTER_SHADOW_GENERATION_NOT_BUILDING")
        invariant(generations.markVerified(row.id, actorId, digest) == 1, "CLUSTER_SHADOW_VERIFY_CAS_FAILED")
        return generations.findByKey(key)
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "CLUSTER_SHADOW_GENERATION_NOT_FOUND")
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailed(key: String, code: String, summary: String) {
        val row = generations.findByKeyForUpdate(key) ?: return
        if (row.generationStatus != "BUILDING") return
        invariant(generations.markFailed(row.id, code, summary) == 1, "CLUSTER_SHADOW_FAILURE_CAS_FAILED")
    }

    private fun invariant(condition: Boolean, code: String) {
        if (!condition) throw ServiceException(ErrorCode.INTERNAL_ERROR, code)
    }

    companion object { private const val BATCH = 200 }
}
