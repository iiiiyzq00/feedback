package com.feedback.repository.feedback.listprojection

import com.feedback.mapper.feedback.listprojection.FeedbackListAiFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListAssignmentFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListAttachmentFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListHandoffFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListWorkflowFactsMapper
import com.feedback.model.po.feedback.listprojection.*
import org.springframework.stereotype.Repository

data class FeedbackListProjectionWorkflowFacts(
    val ai: Map<Long, FeedbackListAiFactsPo>,
    val workflow: Map<Long, FeedbackListWorkflowFactsPo>,
    val assignments: Map<Long, FeedbackListAssignmentFactsPo>,
    val handoffs: Map<Long, FeedbackListHandoffFactsPo>,
    val attachments: Map<Long, FeedbackListAttachmentFactsPo>
)

/** AI、处理流、分发、交接及附件辅助事实。 */
@Repository
class FeedbackListProjectionAuxiliaryWorkflowRepository(
    private val aiMapper: FeedbackListAiFactsMapper,
    private val workflowMapper: FeedbackListWorkflowFactsMapper,
    private val assignmentMapper: FeedbackListAssignmentFactsMapper,
    private val handoffMapper: FeedbackListHandoffFactsMapper,
    private val attachmentMapper: FeedbackListAttachmentFactsMapper
) {
    fun load(ids: List<Long>) = FeedbackListProjectionWorkflowFacts(
        aiMapper.findBySourceIds(ids).associateBy(FeedbackListAiFactsPo::sourceId),
        workflowMapper.findBySourceIds(ids).associateBy(FeedbackListWorkflowFactsPo::feedbackSourceId),
        assignmentMapper.findBySourceIds(ids).associateBy(FeedbackListAssignmentFactsPo::feedbackSourceId),
        handoffMapper.findBySourceIds(ids).associateBy(FeedbackListHandoffFactsPo::feedbackSourceId),
        attachmentMapper.findBySourceIds(ids).associateBy(FeedbackListAttachmentFactsPo::sourceId)
    )
}
