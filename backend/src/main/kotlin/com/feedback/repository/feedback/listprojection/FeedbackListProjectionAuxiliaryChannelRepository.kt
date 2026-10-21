package com.feedback.repository.feedback.listprojection

import com.feedback.mapper.feedback.listprojection.FeedbackListJiraFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListQualityFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListRedblackFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListSdkFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListStationFactsMapper
import com.feedback.model.po.feedback.listprojection.FeedbackListChannelFactsPo
import com.feedback.model.po.feedback.listprojection.FeedbackListSourceFactsPo
import org.springframework.stereotype.Repository

/** 五渠道专用列表事实。 */
@Repository
class FeedbackListProjectionAuxiliaryChannelRepository(
    private val sdkMapper: FeedbackListSdkFactsMapper,
    private val jiraMapper: FeedbackListJiraFactsMapper,
    private val qualityMapper: FeedbackListQualityFactsMapper,
    private val redblackMapper: FeedbackListRedblackFactsMapper,
    private val stationMapper: FeedbackListStationFactsMapper
) {
    fun load(sources: List<FeedbackListSourceFactsPo>): Map<Long, FeedbackListChannelFactsPo> =
        buildList {
            fun ids(channel: String) = sources.filter { it.sourceChannel == channel }.map(FeedbackListSourceFactsPo::id)
            ids("SDK").takeIf(List<Long>::isNotEmpty)?.let { addAll(sdkMapper.findBySourceIds(it)) }
            ids("JIRA").takeIf(List<Long>::isNotEmpty)?.let { addAll(jiraMapper.findBySourceIds(it)) }
            ids("QUALITY").takeIf(List<Long>::isNotEmpty)?.let { addAll(qualityMapper.findBySourceIds(it)) }
            ids("REDBLACK").takeIf(List<Long>::isNotEmpty)?.let { addAll(redblackMapper.findBySourceIds(it)) }
            ids("STATION_FEEDBACK").takeIf(List<Long>::isNotEmpty)?.let { addAll(stationMapper.findBySourceIds(it)) }
        }.associateBy(FeedbackListChannelFactsPo::sourceId)
}
