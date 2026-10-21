package com.feedback.repository.feedback.detail

import com.feedback.application.port.feedback.detail.FeedbackDetailAuditEvent
import com.feedback.application.port.feedback.detail.FeedbackDetailAuditPort
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/** 详情查看审计写适配器；独立写事务在只读快照方法返回后才开启。 */
@Repository
class FeedbackDetailAuditRepository(
    private val repository: FeedbackDetailRepository,
    private val objectMapper: ObjectMapper
) : FeedbackDetailAuditPort {
    @Transactional
    override fun append(event: FeedbackDetailAuditEvent) {
        when (event) {
            is FeedbackDetailAuditEvent.DetailViewed -> repository.appendViewAudit(
                event.userId,
                "VIEW_FEEDBACK_DETAIL",
                event.sourceId,
                objectMapper.writeValueAsString(mapOf("channel" to event.channel))
            )
            is FeedbackDetailAuditEvent.ExternalLinkViewed -> repository.appendViewAudit(
                event.userId,
                "VIEW_EXTERNAL_SOURCE_LINK",
                event.sourceId,
                objectMapper.writeValueAsString(mapOf("channel" to event.channel, "host" to event.host))
            )
        }
    }
}
