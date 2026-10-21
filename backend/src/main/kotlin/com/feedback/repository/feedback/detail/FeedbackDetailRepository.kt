package com.feedback.repository.feedback.detail

import com.feedback.model.po.feedback.detail.*
import org.springframework.stereotype.Repository

/**
 * 反馈详情的单表持久化边界。
 *
 * Repository 只暴露组成详情所需的单表事实；跨表关联、权限和展示规则均由应用层处理。
 * 用户名称采用一次批量读取，导航正文事实也按候选 ID 批量读取，调用方不得退化为逐行查询。
 * 字段历史和归簇时间轴按单个反馈读取并保持数据库稳定排序；当前没有在此层隐式截断集合。
 */
@Repository
class FeedbackDetailRepository(
    private val coreFacts: FeedbackDetailCoreFactsRepository,
    private val workflowFacts: FeedbackDetailWorkflowFactsRepository,
    private val clusterFacts: FeedbackDetailClusterFactsRepository,
    private val channelFacts: FeedbackDetailChannelFactsRepository,
    private val navigation: FeedbackDetailNavigationRepository
) {
    fun source(sourceId: Long) = coreFacts.source(sourceId)
    fun content(sourceId: Long) = coreFacts.content(sourceId)
    fun ai(sourceId: Long) = coreFacts.ai(sourceId)
    fun importBatch(id: Long) = coreFacts.importBatch(id)
    fun importRow(sourceId: Long) = coreFacts.importRow(sourceId)
    fun workflow(sourceId: Long) = workflowFacts.workflow(sourceId)
    fun assignment(sourceId: Long) = workflowFacts.assignment(sourceId)
    fun users(ids: Set<Long>): Map<Long, FeedbackDetailUserPo> = workflowFacts.users(ids)
    fun fieldHistory(sourceId: Long) = workflowFacts.fieldHistory(sourceId)
    fun timelineClusterLinks(sourceId: Long) = clusterFacts.timelineClusterLinks(sourceId)
    fun clusterSummary(sourceId: Long) = clusterFacts.clusterSummary(sourceId)
    fun identityTypes(sourceId: Long): List<String> = channelFacts.identityTypes(sourceId)
    fun sdk(sourceId: Long) = channelFacts.sdk(sourceId)
    fun jira(sourceId: Long) = channelFacts.jira(sourceId)
    fun quality(sourceId: Long) = channelFacts.quality(sourceId)
    fun redblack(sourceId: Long) = channelFacts.redblack(sourceId)
    fun station(sourceId: Long) = channelFacts.station(sourceId)
    fun activeConfigString(key: String) = channelFacts.activeConfigString(key)

    fun previousCandidates(
        cursor: FeedbackNavigationSourcePo,
        channels: Set<String>,
        regions: Set<String>?,
        limit: Int
    ) = navigation.previousCandidates(cursor, channels, regions, limit)

    fun nextCandidates(
        cursor: FeedbackNavigationSourcePo,
        channels: Set<String>,
        regions: Set<String>?,
        limit: Int
    ) = navigation.nextCandidates(cursor, channels, regions, limit)

    fun navigationContent(sourceIds: List<Long>) = navigation.navigationContent(sourceIds)

    fun appendViewAudit(userId: Long, operation: String, sourceId: Long, detailJson: String) =
        workflowFacts.appendViewAudit(userId, operation, sourceId, detailJson)
}
