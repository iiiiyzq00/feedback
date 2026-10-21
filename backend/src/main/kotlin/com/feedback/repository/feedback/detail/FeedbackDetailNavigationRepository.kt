package com.feedback.repository.feedback.detail

import com.feedback.mapper.feedback.detail.FeedbackDetailContentMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailSourceMapper
import com.feedback.model.po.feedback.detail.FeedbackNavigationSourcePo
import org.springframework.stereotype.Repository

/** 相邻反馈的有界 keyset 候选与安全正文资格事实。 */
@Repository
class FeedbackDetailNavigationRepository(
    private val sourceMapper: FeedbackDetailSourceMapper,
    private val contentMapper: FeedbackDetailContentMapper
) {
    fun previousCandidates(cursor: FeedbackNavigationSourcePo, channels: Set<String>, regions: Set<String>?, limit: Int) =
        sourceMapper.findPreviousCandidates(cursor, channels, regions, limit)
    fun nextCandidates(cursor: FeedbackNavigationSourcePo, channels: Set<String>, regions: Set<String>?, limit: Int) =
        sourceMapper.findNextCandidates(cursor, channels, regions, limit)
    fun navigationContent(sourceIds: List<Long>) = contentMapper.findNavigationFacts(sourceIds)
}
