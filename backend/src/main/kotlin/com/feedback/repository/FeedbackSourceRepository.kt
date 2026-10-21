package com.feedback.repository

import com.feedback.model.feedback.FeedbackSource
import com.feedback.mapper.core.CoreFeedbackSourceMapper
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional

@Repository
class FeedbackSourceRepository(private val mapper: CoreFeedbackSourceMapper) {
    fun findById(id: Long): Optional<FeedbackSource> = Optional.ofNullable(mapper.findById(id))
    fun findAllById(ids: Iterable<Long>): List<FeedbackSource> {
        val values = ids.toList().distinct()
        return if (values.isEmpty()) emptyList() else mapper.findByIds(values)
    }
    fun existsById(id: Long): Boolean = mapper.findById(id) != null
    fun save(row: FeedbackSource): FeedbackSource {
        val affected = if (row.id == 0L) mapper.insert(row) else mapper.update(row)
        check(affected in 0..1) { "FEEDBACK_SOURCE_SAVE_AFFECTED_ROWS_INVALID" }
        return row
    }
    fun saveAndFlush(row: FeedbackSource): FeedbackSource = save(row)
    fun findBySourceChannelAndSourceRecordId(channel: String, recordId: String): FeedbackSource? =
        mapper.findByChannelRecord(channel, recordId)
    fun findBySourceChannelAndSourceEventTimeBetween(channel: String, start: LocalDateTime, end: LocalDateTime) =
        mapper.findByChannelTime(channel, start, end)
    fun countBySourceChannel(channel: String): Long = mapper.countByChannel(channel)
    fun findByImportBatchId(importBatchId: Long): List<FeedbackSource> = mapper.findByImportBatchId(importBatchId)
}
