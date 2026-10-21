package com.feedback.repository.feedback.access

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.attachment.AttachmentGrantMapper
import com.feedback.mapper.audit.AttachmentAuditMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailAssignmentMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailAuditMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailUserMapper
import com.feedback.mapper.feedback.detail.FeedbackDetailWorkflowMapper
import com.feedback.application.port.feedback.attachment.AttachmentAuthorizationAuditEvent
import com.feedback.application.port.feedback.attachment.AttachmentGrantFingerprint
import com.feedback.repository.impl.attachment.MyBatisAttachmentGrantRepositoryImpl
import com.feedback.application.port.feedback.attachment.CreateAttachmentGrantCommand
import com.feedback.repository.audit.AttachmentAuditRepository
import com.feedback.repository.feedback.detail.FeedbackDetailWorkflowFactsRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** 冻结反馈持久化写入行数异常的统一内部错误合同，避免重新泄漏标准状态异常。 */
class PersistenceFailureContractTest {
    @Test
    fun `attachment grant insert mismatch is an internal service failure`() {
        val mapper = mockk<AttachmentGrantMapper>()
        every { mapper.insert(any()) } returns 0

        val error = assertThrows(ServiceException::class.java) {
            MyBatisAttachmentGrantRepositoryImpl(mapper).create(
                CreateAttachmentGrantCommand(
                    sourceId = 11,
                    attachmentId = 12,
                    userId = 13,
                    fingerprint = AttachmentGrantFingerprint.fromToken("token"),
                    expiresAt = LocalDateTime.of(2026, 9, 7, 12, 0),
                    accessReason = "reason-sha256"
                )
            )
        }

        assertInternal(error, "ATTACHMENT_GRANT_CREATE_FAILED")
    }

    @Test
    fun `attachment audit insert mismatch is an internal service failure`() {
        val mapper = mockk<AttachmentAuditMapper>()
        every { mapper.insertAccessAudit(any(), any(), any(), any(), any()) } returns 0

        val error = assertThrows(ServiceException::class.java) {
            AttachmentAuditRepository(mapper, jacksonObjectMapper()).append(
                AttachmentAuthorizationAuditEvent(
                    13,
                    12,
                    11,
                    com.feedback.common.AttachmentAuthorizationAction.DIRECT_VIEW,
                    "hash",
                    4,
                    true,
                    60,
                    com.feedback.common.AttachmentAuditOperation.ATTACHMENT_DECRYPT
                )
            )
        }

        assertInternal(error, "ATTACHMENT_ACCESS_AUDIT_FAILED")
    }

    @Test
    fun `detail audit insert mismatch is an internal service failure`() {
        val audits = mockk<FeedbackDetailAuditMapper>()
        every { audits.insertAccessAudit(any(), any(), any(), any(), any()) } returns 0
        val repository = FeedbackDetailWorkflowFactsRepository(
            mockk<FeedbackDetailWorkflowMapper>(),
            mockk<FeedbackDetailAssignmentMapper>(),
            mockk<FeedbackDetailUserMapper>(),
            audits
        )

        val error = assertThrows(ServiceException::class.java) {
            repository.appendViewAudit(13, "VIEW_FEEDBACK_DETAIL", 11, "{}")
        }

        assertInternal(error, "FEEDBACK_DETAIL_AUDIT_WRITE_FAILED")
    }

    private fun assertInternal(error: ServiceException, reasonCode: String) {
        assertEquals(ErrorCode.INTERNAL_ERROR, error.errorCode)
        assertEquals(reasonCode, error.reasonCode)
    }
}
