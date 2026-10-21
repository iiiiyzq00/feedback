package com.feedback.repository.feedback.detail

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.feedback.detail.FeedbackDetailAssignmentMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailAuditMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailUserMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailWorkflowMapper
import com.feedback.model.po.feedback.detail.FeedbackDetailUserPo
import org.springframework.stereotype.Repository

/** 详情处理流程、负责人和访问审计事实。 */
@Repository
class FeedbackDetailWorkflowFactsRepository(
    private val workflowMapper: FeedbackDetailWorkflowMapper,
    private val assignmentMapper: FeedbackDetailAssignmentMapper,
    private val userMapper: FeedbackDetailUserMapper,
    private val auditMapper: FeedbackDetailAuditMapper
) {
    fun workflow(sourceId: Long) = workflowMapper.findBySourceId(sourceId)
    fun assignment(sourceId: Long) = assignmentMapper.findBySourceId(sourceId)
    fun users(ids: Set<Long>): Map<Long, FeedbackDetailUserPo> =
        if (ids.isEmpty()) emptyMap() else userMapper.findByIds(ids).associateBy(FeedbackDetailUserPo::id)
    fun fieldHistory(sourceId: Long) = auditMapper.findFieldHistory(sourceId.toString())
    fun appendViewAudit(userId: Long, operation: String, sourceId: Long, detailJson: String) {
        if (auditMapper.insertAccessAudit(userId, operation, "FEEDBACK", sourceId.toString(), detailJson) != 1) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "FEEDBACK_DETAIL_AUDIT_WRITE_FAILED")
        }
    }
}
