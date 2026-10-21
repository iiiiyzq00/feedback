package com.feedback.repository.feedback.listprojection

import com.feedback.mapper.feedback.listprojection.FeedbackClusterIdentityProjectionMapper
import com.feedback.mapper.feedback.listprojection.FeedbackClusterMemberProjectionMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListProjectionMapper
import com.feedback.model.po.feedback.listprojection.FeedbackClusterIdentityProjectionPo
import com.feedback.model.po.feedback.listprojection.FeedbackClusterMemberProjectionPo
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionPo
import org.springframework.stereotype.Repository

/** 投影主体、成员与身份投影的批量存储边界。 */
@Repository
class FeedbackListProjectionStoreRepository(
    private val projectionMapper: FeedbackListProjectionMapper,
    private val memberMapper: FeedbackClusterMemberProjectionMapper,
    private val identityMapper: FeedbackClusterIdentityProjectionMapper
) {
    fun projectionPage(generationId: Long, afterSourceId: Long, limit: Int): List<FeedbackListProjectionPo> {
        requireLimit(limit)
        return projectionMapper.findAfterSourceId(generationId, afterSourceId, limit).also { rows ->
            if (rows.map(FeedbackListProjectionPo::sourceId) != rows.map(FeedbackListProjectionPo::sourceId).distinct().sorted()) {
                projectionBuildFailed("FEEDBACK_LIST_PROJECTION_SCAN_ORDER_INVALID")
            }
        }
    }
    fun memberPage(generationId: Long, afterSourceId: Long, limit: Int): List<FeedbackClusterMemberProjectionPo> {
        requireLimit(limit)
        return memberMapper.findAfterSourceId(generationId, afterSourceId, limit).also { rows ->
            if (rows.map(FeedbackClusterMemberProjectionPo::sourceId) != rows.map(FeedbackClusterMemberProjectionPo::sourceId).distinct().sorted()) {
                projectionBuildFailed("FEEDBACK_LIST_MEMBER_PROJECTION_SCAN_ORDER_INVALID")
            }
        }
    }
    fun identityPage(generationId: Long, afterSourceId: Long, afterIdentityKey: String, limit: Int): List<FeedbackClusterIdentityProjectionPo> {
        requireLimit(limit)
        return identityMapper.findAfterKey(generationId, afterSourceId, afterIdentityKey, limit)
    }
    fun replaceBatch(generationId: Long, requestedSourceIds: List<Long>, projections: List<FeedbackListProjectionPo>,
                     members: List<FeedbackClusterMemberProjectionPo>, identities: List<FeedbackClusterIdentityProjectionPo>) {
        val requested = boundedIds(requestedSourceIds)
        if (requested.isEmpty()) return
        projectionMapper.deleteBySourceIds(generationId, requested)
        memberMapper.deleteBySourceIds(generationId, requested)
        identityMapper.deleteBySourceIds(generationId, requested)
        if (projections.isNotEmpty() && projectionMapper.upsertAll(projections) < 0) projectionBuildFailed("FEEDBACK_LIST_PROJECTION_UPSERT_FAILED")
        if (members.isNotEmpty() && memberMapper.upsertAll(members) < 0) projectionBuildFailed("FEEDBACK_LIST_CLUSTER_MEMBER_UPSERT_FAILED")
        if (identities.isNotEmpty() && identityMapper.upsertAll(identities) < 0) projectionBuildFailed("FEEDBACK_LIST_CLUSTER_IDENTITY_UPSERT_FAILED")
    }
    private fun requireLimit(limit: Int) {
        if (limit !in 1..FeedbackListProjectionRepository.MAX_BATCH_SIZE) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_BATCH_LIMIT_INVALID")
    }
}

internal fun boundedIds(ids: List<Long>): List<Long> {
    val normalized = ids.distinct()
    if (normalized.size > FeedbackListProjectionRepository.MAX_BATCH_SIZE) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_BATCH_TOO_LARGE")
    if (normalized.any { it <= 0 }) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_SOURCE_ID_INVALID")
    return normalized
}
