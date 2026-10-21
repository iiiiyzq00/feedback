package com.feedback.repository

import com.feedback.model.imports.ImportRow
import com.feedback.mapper.imports.ImportFeedbackSourceLookupMapper
import com.feedback.mapper.imports.ImportRowMapper
import org.springframework.stereotype.Repository
import java.util.Optional

/** 保持原批处理服务调用面的 `t_import_row` 单表 MyBatis Repository。 */
@Repository
class ImportRowRepository(
    private val mapper: ImportRowMapper,
    private val batches: ImportBatchRepository,
    private val sources: ImportFeedbackSourceLookupMapper
) {
    fun save(row: ImportRow): ImportRow {
        if (row.id == 0L) check(mapper.insert(row) == 1) else check(mapper.update(row) == 1)
        return row
    }
    fun flush() = Unit
    fun findById(id: Long): Optional<ImportRow> = Optional.ofNullable(mapper.findById(id))
    fun findByBatchId(batchId: Long) = mapper.findByBatchId(batchId)
    fun findByBatchIdAndRecordType(batchId: Long, recordType: String) = mapper.findByBatchAndRecordType(batchId, recordType)
    fun findByBatchIdAndRowStatusIn(batchId: Long, statuses: Collection<String>) = mapper.findByBatchAndStatuses(batchId, statuses)
    fun findTop500ByBatchIdAndRowStatusOrderById(batchId: Long, status: String) = mapper.findTopByBatchStatus(batchId, status, 500)
    fun countByBatchIdAndRecordTypeAndRowStatusIn(batchId: Long, recordType: String, statuses: Collection<String>) =
        mapper.countByBatchTypeStatuses(batchId, recordType, statuses)
    fun countByBatchIdAndRecordTypeAndRowStatus(batchId: Long, recordType: String, status: String) =
        mapper.countByBatchTypeStatuses(batchId, recordType, listOf(status))
    fun resetRetryableRows(batchId: Long) = mapper.resetRetryableRows(batchId)
    fun resetRowsByStatuses(batchId: Long, statuses: Collection<String>) = mapper.resetRowsByStatuses(batchId, statuses)
    fun findFirstByFeedbackSourceIdOrderByCreatedAtDesc(sourceId: Long) = mapper.findLatestByFeedbackSourceId(sourceId)
    fun findLatestActiveReviewByBusinessKey(sourceChannel: String, businessKeyHash: ByteArray, rowId: Long): ImportRow? {
        val ids = batches.activeIdsByChannel(sourceChannel)
        return if (ids.isEmpty()) null else mapper.findLatestReview(ids, businessKeyHash, rowId)
    }
    fun markJiraFormalReviewUnavailable(keys: Collection<String>): Int {
        if (keys.isEmpty()) return 0
        val ids = sources.findJiraIdsByKeys(keys)
        return if (ids.isEmpty()) 0 else mapper.markJiraUnavailableBySourceIds(ids)
    }
}
