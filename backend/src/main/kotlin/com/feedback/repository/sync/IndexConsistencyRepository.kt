package com.feedback.repository.sync

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.sync.IndexAiTaskMapper
import com.feedback.mapper.sync.IndexClusterMapper
import com.feedback.mapper.sync.IndexConsistencyAuditMapper
import com.feedback.mapper.sync.IndexFeedbackAiMapper
import org.springframework.stereotype.Repository

@Repository
class IndexConsistencyRepository(
    private val tasks: IndexAiTaskMapper,
    private val feedback: IndexFeedbackAiMapper,
    private val clusters: IndexClusterMapper,
    private val audits: IndexConsistencyAuditMapper
) {
    fun outboxStatus(): List<Map<String, Any>> = tasks.statusCounts().map { mapOf("status" to it.status, "count" to it.count) }
    fun feedbackPending(): Long = feedback.countPending()
    fun clusterPending(): Long = clusters.countPending()
    fun replay(taskId: Long, reason: String): Boolean = tasks.replay(taskId, reason) == 1
    fun audit(operator: Long, operation: String, targetType: String, targetId: String?, detailJson: String) {
        if(audits.insert(operator, operation, targetType, targetId, detailJson) != 1)
            throw ServiceException(ErrorCode.INTERNAL_ERROR,"INDEX_CONSISTENCY_AUDIT_WRITE_FAILED")
    }
}
