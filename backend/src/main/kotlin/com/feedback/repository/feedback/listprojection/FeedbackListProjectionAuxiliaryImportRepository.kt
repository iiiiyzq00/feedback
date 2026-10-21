package com.feedback.repository.feedback.listprojection

import com.feedback.mapper.feedback.listprojection.FeedbackListImportBatchFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListImportRowFactsMapper
import com.feedback.model.po.feedback.listprojection.FeedbackListImportBatchFactsPo
import com.feedback.model.po.feedback.listprojection.FeedbackListImportRowFactsPo
import com.feedback.model.po.feedback.listprojection.FeedbackListSourceFactsPo
import org.springframework.stereotype.Repository

data class FeedbackListProjectionImportFacts(
    val batches: Map<Long, FeedbackListImportBatchFactsPo>,
    val rows: Map<Long, List<FeedbackListImportRowFactsPo>>
)

/** 导入批次及导入行辅助事实。 */
@Repository
class FeedbackListProjectionAuxiliaryImportRepository(
    private val batchMapper: FeedbackListImportBatchFactsMapper,
    private val rowMapper: FeedbackListImportRowFactsMapper
) {
    fun load(sources: List<FeedbackListSourceFactsPo>, ids: List<Long>): FeedbackListProjectionImportFacts {
        val rows = rowMapper.findBySourceIds(ids)
        val batchIds = (sources.mapNotNull(FeedbackListSourceFactsPo::importBatchId) + rows.map(FeedbackListImportRowFactsPo::batchId)).distinct()
        val batches = if (batchIds.isEmpty()) emptyMap() else batchMapper.findByIds(batchIds).associateBy(FeedbackListImportBatchFactsPo::id)
        return FeedbackListProjectionImportFacts(batches, rows.groupBy(FeedbackListImportRowFactsPo::feedbackSourceId))
    }
}
