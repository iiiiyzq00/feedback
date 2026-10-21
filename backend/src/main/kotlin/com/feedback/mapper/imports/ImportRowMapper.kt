package com.feedback.mapper.imports

import com.feedback.model.imports.ImportRow
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface ImportRowMapper {
    fun insert(row: ImportRow): Int
    fun update(row: ImportRow): Int
    fun findById(id: Long): ImportRow?
    fun findByBatchId(batchId: Long): List<ImportRow>
    fun findByBatchAndRecordType(@Param("batchId") batchId: Long, @Param("recordType") recordType: String): List<ImportRow>
    fun findByBatchAndStatuses(@Param("batchId") batchId: Long, @Param("statuses") statuses: Collection<String>): List<ImportRow>
    fun findTopByBatchStatus(@Param("batchId") batchId: Long, @Param("status") status: String, @Param("limit") limit: Int): List<ImportRow>
    fun countByBatchTypeStatuses(@Param("batchId") batchId: Long, @Param("recordType") recordType: String, @Param("statuses") statuses: Collection<String>): Long
    fun resetRetryableRows(batchId: Long): Int
    fun resetRowsByStatuses(@Param("batchId") batchId: Long, @Param("statuses") statuses: Collection<String>): Int
    fun findLatestByFeedbackSourceId(feedbackSourceId: Long): ImportRow?
    fun findLatestReview(@Param("batchIds") batchIds: List<Long>, @Param("businessKeyHash") businessKeyHash: ByteArray, @Param("rowId") rowId: Long): ImportRow?
    fun markJiraUnavailableBySourceIds(@Param("sourceIds") sourceIds: List<Long>): Int
}
