package com.feedback.repository.feedback.listprojection

import com.feedback.mapper.feedback.listprojection.FeedbackListContentFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListOwnerFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListSourceFactsMapper
import com.feedback.model.po.feedback.listprojection.FeedbackListContentFactsPo
import com.feedback.model.po.feedback.listprojection.FeedbackListOwnerFactsPo
import com.feedback.model.po.feedback.listprojection.FeedbackListSourceFactsPo
import org.springframework.stereotype.Repository

/** 来源 keyset、正文资格和实时负责人名称事实。 */
@Repository
class FeedbackListProjectionSourceFactsRepository(
    private val sourceMapper: FeedbackListSourceFactsMapper,
    private val contentMapper: FeedbackListContentFactsMapper,
    private val ownerMapper: FeedbackListOwnerFactsMapper
) {
    fun sourceFence(): Long = sourceMapper.findMaxId() ?: 0L
    fun sourceCountThroughFence(fenceSourceId: Long): Long {
        if (fenceSourceId < 0) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_FENCE_INVALID")
        return sourceMapper.countThroughId(fenceSourceId)
    }
    fun nextSourceIds(afterSourceId: Long, fenceSourceId: Long, limit: Int): List<Long> {
        if (afterSourceId < 0 || fenceSourceId < afterSourceId) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_KEYSET_RANGE_INVALID")
        if (limit !in 1..FeedbackListProjectionRepository.MAX_BATCH_SIZE) invalidProjectionInput("FEEDBACK_LIST_PROJECTION_BATCH_LIMIT_INVALID")
        return sourceMapper.findIdsAfterThroughFence(afterSourceId, fenceSourceId, limit).also { ids ->
            if (ids != ids.distinct().sorted() || ids.any { it <= afterSourceId || it > fenceSourceId }) {
                projectionBuildFailed("FEEDBACK_LIST_PROJECTION_KEYSET_RESULT_INVALID")
            }
        }
    }
    fun sources(sourceIds: List<Long>): Map<Long, FeedbackListSourceFactsPo> =
        bounded(sourceIds) { sourceMapper.findByIds(it) }.associateBy(FeedbackListSourceFactsPo::id)
    fun contents(sourceIds: List<Long>): Map<Long, FeedbackListContentFactsPo> =
        bounded(sourceIds) { contentMapper.findBySourceIds(it) }.associateBy(FeedbackListContentFactsPo::sourceId)
    fun owners(ownerIds: List<Long>): Map<Long, FeedbackListOwnerFactsPo> =
        bounded(ownerIds) { ownerMapper.findByIds(it) }.associateBy(FeedbackListOwnerFactsPo::id)
    private fun <T> bounded(ids: List<Long>, query: (List<Long>) -> List<T>): List<T> =
        boundedIds(ids).let { if (it.isEmpty()) emptyList() else query(it) }
}
