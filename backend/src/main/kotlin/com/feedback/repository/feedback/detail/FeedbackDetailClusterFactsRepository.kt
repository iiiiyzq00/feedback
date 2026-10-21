package com.feedback.repository.feedback.detail

import com.feedback.mapper.feedback.detail.FeedbackDetailClusterLinkMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailClusterMapper
import com.feedback.model.po.feedback.detail.FeedbackClusterLinkSummaryPo
import com.feedback.model.po.feedback.detail.FeedbackClusterSummaryPo
import org.springframework.stereotype.Repository

/** 当前归簇摘要及完整归簇时间轴事实。 */
@Repository
class FeedbackDetailClusterFactsRepository(
    private val clusterLinkMapper: FeedbackDetailClusterLinkMapper,
    private val clusterMapper: FeedbackDetailClusterMapper
) {
    fun timelineClusterLinks(sourceId: Long) = clusterLinkMapper.findTimelineBySourceId(sourceId)
    fun clusterSummary(sourceId: Long): Pair<FeedbackClusterLinkSummaryPo, FeedbackClusterSummaryPo>? {
        val link = clusterLinkMapper.findActiveMain(sourceId) ?: return null
        val cluster = clusterMapper.findById(link.clusterId) ?: return null
        return link to cluster
    }
}
