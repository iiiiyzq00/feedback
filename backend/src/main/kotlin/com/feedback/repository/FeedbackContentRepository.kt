package com.feedback.repository

import com.feedback.model.feedback.FeedbackContent
import com.feedback.mapper.core.CoreFeedbackContentMapper
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class FeedbackContentRepository(private val mapper: CoreFeedbackContentMapper) {
    fun findById(id: Long): Optional<FeedbackContent> = Optional.ofNullable(mapper.findById(id))
    fun findAllById(ids: Iterable<Long>): List<FeedbackContent> {
        val values = ids.toList().distinct()
        return if (values.isEmpty()) emptyList() else mapper.findByIds(values)
    }
    fun save(row: FeedbackContent): FeedbackContent {
        val affected = mapper.upsert(row)
        check(affected in 0..2) { "FEEDBACK_CONTENT_UPSERT_AFFECTED_ROWS_INVALID" }
        return row
    }
    fun saveAndFlush(row: FeedbackContent): FeedbackContent = save(row)
    fun findByMaskingStatus(status: String): List<FeedbackContent> = mapper.findByMaskingStatus(status)
    fun findByProcessingStatus(status: String): List<FeedbackContent> = mapper.findByProcessingStatus(status)
}
