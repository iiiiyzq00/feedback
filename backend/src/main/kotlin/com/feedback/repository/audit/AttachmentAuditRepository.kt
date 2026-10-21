package com.feedback.repository.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.audit.AttachmentAuditMapper
import com.feedback.application.port.feedback.attachment.AttachmentAuthorizationAuditEvent
import com.feedback.application.port.feedback.attachment.AttachmentAuthorizationAuditPort
import org.springframework.stereotype.Repository

private data class AttachmentAccessAuditDetail(
    val sourceId: Long,
    val reasonCode: String,
    val reasonSha256: String,
    val reasonLength: Int,
    val grantHashRecorded: Boolean,
    val expiresInSeconds: Long
)

@Repository
class AttachmentAuditRepository(
    private val mapper: AttachmentAuditMapper,
    private val objectMapper: ObjectMapper
) : AttachmentAuthorizationAuditPort {
    /** 将强类型动作转换为稳定 code，并以单条插入写入不含令牌原文的审计。 */
    override fun append(event: AttachmentAuthorizationAuditEvent) {
        val detail = AttachmentAccessAuditDetail(
            sourceId = event.sourceId,
            reasonCode = event.reasonCode.code,
            reasonSha256 = event.reasonSha256,
            reasonLength = event.reasonLength,
            grantHashRecorded = event.grantHashRecorded,
            expiresInSeconds = event.expiresInSeconds
        )
        val inserted = mapper.insertAccessAudit(
            userId = event.userId,
            operation = event.operation.code,
            targetType = "FEEDBACK_ATTACHMENT",
            targetId = event.attachmentId.toString(),
            detailJson = objectMapper.writeValueAsString(detail)
        )
        if (inserted != 1) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "ATTACHMENT_ACCESS_AUDIT_FAILED")
        }
    }
}
