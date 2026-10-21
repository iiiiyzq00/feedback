package com.feedback.repository.feedback.detail

import com.feedback.mapper.feedback.detail.FeedbackDetailAiMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailContentMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailImportBatchMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailSourceMapper
import com.feedback.mapper.feedback.detail.FeedbackTimelineImportRowMapper
import org.springframework.stereotype.Repository

/** 详情主体、AI 与导入来源事实。 */
@Repository
class FeedbackDetailCoreFactsRepository(
    private val sourceMapper: FeedbackDetailSourceMapper,
    private val contentMapper: FeedbackDetailContentMapper,
    private val aiMapper: FeedbackDetailAiMapper,
    private val importBatchMapper: FeedbackDetailImportBatchMapper,
    private val importRowMapper: FeedbackTimelineImportRowMapper
) {
    fun source(sourceId: Long) = sourceMapper.findById(sourceId)
    fun content(sourceId: Long) = contentMapper.findBySourceId(sourceId)
    fun ai(sourceId: Long) = aiMapper.findBySourceId(sourceId)
    fun importBatch(id: Long) = importBatchMapper.findById(id)
    fun importRow(sourceId: Long) = importRowMapper.findFirstBySourceId(sourceId)
}
