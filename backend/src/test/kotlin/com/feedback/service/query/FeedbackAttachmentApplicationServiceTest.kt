package com.feedback.service.query

import com.feedback.common.*
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.security.WebPrincipal
import com.feedback.security.ContentVisibility
import com.feedback.model.attachment.AttachmentAccessGrant
import com.feedback.model.attachment.AttachmentDeliveryMode
import com.feedback.application.port.feedback.attachment.AttachmentAuthorizationAuditEvent
import com.feedback.application.port.feedback.attachment.AttachmentAuthorizationAuditPort
import com.feedback.application.port.feedback.attachment.AttachmentAuthorizationFacts
import com.feedback.manager.attachment.AttachmentGrantIssuePort
import com.feedback.application.port.feedback.attachment.AttachmentListFacts
import com.feedback.model.attachment.AuthorizeFeedbackAttachmentCommand
import com.feedback.model.attachment.AuthorizeFeedbackAttachmentPreviewsCommand
import com.feedback.security.attachment.FeedbackAttachmentAccessPort
import com.feedback.model.attachment.FeedbackAttachmentListQuery
import com.feedback.application.port.feedback.attachment.FeedbackAttachmentReadPort
import com.feedback.model.attachment.IssueAttachmentGrantCommand
import com.feedback.application.port.feedback.attachment.AttachmentMediaPreviewPriorityPort
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import com.feedback.service.impl.attachment.FeedbackAttachmentApplicationServiceImpl
import com.feedback.service.attachment.FeedbackAttachmentApplicationService

class FeedbackAttachmentApplicationServiceTest {
    private val access = mockk<FeedbackAttachmentAccessPort>()
    private val attachmentRepository = mockk<FeedbackAttachmentReadPort>()
    private val auditRepository = mockk<AttachmentAuthorizationAuditPort>()
    private val grants = mockk<AttachmentGrantIssuePort>()
    private val previewPriority = mockk<AttachmentMediaPreviewPriorityPort>(relaxed = true)
    private val principal = WebPrincipal(
        userId = 7,
        loginName = "feedback-user",
        passwordHash = "hash",
        displayName = "反馈用户",
        roles = setOf("PHASE_ONE_TECHNICAL"),
        permissions = setOf("access:phase_one_workspace"),
        authProvider = "SSO"
    )

    @Test
    fun `authorized in-scope attachment creates grant and audit without jdbc access`() {
        val audit = slot<AttachmentAuthorizationAuditEvent>()
        every { access.requireScope(11) } just Runs
        every { access.principal() } returns principal
        every { attachmentRepository.findAuthorizationFacts(22, 11) } returns availableAttachment()
        val grant = AttachmentAccessGrant(
            grantId = "g".repeat(43),
            expiresAt = LocalDateTime.of(2026, 8, 29, 16, 30),
            expiresInSeconds = 300,
            delivery = AttachmentDeliveryMode.ONE_TIME_SECURE_STREAM,
            downloadUrl = "/api/v1/feedback/11/attachments/22/stream?grantId=${"g".repeat(43)}"
        )
        every { grants.issue(any()) } returns grant
        every { auditRepository.append(capture(audit)) } just Runs

        service().authorize(AuthorizeFeedbackAttachmentCommand(11, 22, AttachmentGrantPurpose.INLINE_VIEW))

        verify(exactly = 1) {
            grants.issue(
                IssueAttachmentGrantCommand(
                    11,
                    22,
                    AttachmentAuthorizationAction.DIRECT_VIEW,
                    principal,
                    AttachmentGrantPurpose.INLINE_VIEW
                )
            )
        }
        verify(exactly = 1) { auditRepository.append(any()) }
        assertEquals(AttachmentAuthorizationAction.DIRECT_VIEW, audit.captured.reasonCode)
        assertEquals("DIRECT_VIEW".length, audit.captured.reasonLength)
        assertEquals(64, audit.captured.reasonSha256.length)
        assertEquals(false, audit.captured.toString().contains("查看理由"))
    }

    @Test
    fun `original log authorization keeps raw role type safety purpose and audit isolated`() {
        val rawPrincipal = principal.copy(permissions = principal.permissions + ContentVisibility.RAW_PERMISSION)
        val audit = slot<AttachmentAuthorizationAuditEvent>()
        val grant = grant(AttachmentDeliveryMode.ONE_TIME_ORIGINAL_DOWNLOAD, "/original?grantId=${"o".repeat(43)}")
        every { access.requireScope(11) } just Runs
        every { attachmentRepository.findAuthorizationFacts(22, 11) } returns
            availableAttachment().copy(attachmentType = AttachmentType.LOG)
        every { access.principal() } returns rawPrincipal
        every { grants.issue(any()) } returns grant
        every { auditRepository.append(capture(audit)) } just Runs

        val actual = service().authorize(
            AuthorizeFeedbackAttachmentCommand(11, 22, AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD)
        )

        assertSame(grant, actual)
        verifyOrder {
            access.requireScope(11)
            attachmentRepository.findAuthorizationFacts(22, 11)
            access.principal()
            grants.issue(IssueAttachmentGrantCommand(
                11,
                22,
                AttachmentAuthorizationAction.ORIGINAL_LOG_DOWNLOAD,
                rawPrincipal,
                AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD
            ))
            auditRepository.append(any())
        }
        assertEquals(AttachmentAuditOperation.ORIGINAL_LOG_DOWNLOAD, audit.captured.operation)
        assertEquals(AttachmentAuthorizationAction.ORIGINAL_LOG_DOWNLOAD, audit.captured.reasonCode)
    }

    @Test
    fun `derived preview authorization retains live gates purpose isolation and audit`() {
        val audit = slot<AttachmentAuthorizationAuditEvent>()
        val grant = grant(AttachmentDeliveryMode.ONE_TIME_DERIVED_PREVIEW, "/preview?grantId=${"p".repeat(43)}")
        every { access.requireScope(11) } just Runs
        every { attachmentRepository.findAuthorizationFacts(22, 11) } returns
            availableAttachment().copy(mediaPreviewStatus = AttachmentPreviewStatus.AVAILABLE)
        every { access.principal() } returns principal
        every { grants.issue(any()) } returns grant
        every { auditRepository.append(capture(audit)) } just Runs

        val actual = service().authorize(
            AuthorizeFeedbackAttachmentCommand(11, 22, AttachmentGrantPurpose.DERIVED_PREVIEW)
        )

        assertSame(grant, actual)
        verify(exactly = 1) {
            grants.issue(
                IssueAttachmentGrantCommand(
                    11,
                    22,
                    AttachmentAuthorizationAction.DERIVED_PREVIEW,
                    principal,
                    AttachmentGrantPurpose.DERIVED_PREVIEW
                )
            )
        }
        assertEquals(AttachmentAuditOperation.ATTACHMENT_PREVIEW, audit.captured.operation)
    }

    @Test
    fun `derived preview authorization does not fall back to original while preview is pending`() {
        every { access.requireScope(11) } just Runs
        every { attachmentRepository.findAuthorizationFacts(22, 11) } returns
            availableAttachment().copy(mediaPreviewStatus = AttachmentPreviewStatus.PENDING)

        val error = assertThrows(ClientException::class.java) {
            service().authorize(AuthorizeFeedbackAttachmentCommand(11, 22, AttachmentGrantPurpose.DERIVED_PREVIEW))
        }

        assertEquals("ATTACHMENT_PREVIEW_NOT_AVAILABLE", error.reasonCode)
        verify(exactly = 0) { grants.issue(any()) }
    }

    @Test
    fun `batch preview authorization issues isolated grants and only prioritizes pending visible media`() {
        every { access.requireScope(11) } just Runs
        every { access.principal() } returns principal
        every { attachmentRepository.findAuthorizationFacts(listOf(22L, 23L), 11) } returns listOf(
            availableAttachment().copy(mediaPreviewStatus = AttachmentPreviewStatus.AVAILABLE),
            availableAttachment().copy(
                id = 23,
                attachmentType = AttachmentType.VIDEO,
                mediaPreviewStatus = AttachmentPreviewStatus.PENDING
            )
        )
        every { grants.issue(any()) } returns grant(
            AttachmentDeliveryMode.ONE_TIME_DERIVED_PREVIEW,
            "/preview?grantId=${"p".repeat(43)}"
        )
        every { auditRepository.append(any()) } just Runs
        every { previewPriority.prioritize(11, listOf(23L)) } returns 1

        val result = service().authorizePreviews(
            AuthorizeFeedbackAttachmentPreviewsCommand(11, listOf(22, 23, 22))
        )

        assertEquals(listOf(22L, 23L), result.items.map { it.attachmentId })
        assertEquals(listOf(AttachmentPreviewStatus.AVAILABLE, AttachmentPreviewStatus.PENDING), result.items.map { it.status })
        assertEquals(AttachmentDeliveryMode.ONE_TIME_DERIVED_PREVIEW, result.items.first().grant?.delivery)
        verify(exactly = 1) { grants.issue(any()) }
        verify(exactly = 1) { auditRepository.append(any()) }
        verify(exactly = 1) { previewPriority.prioritize(11, listOf(23L)) }
    }

    @Test
    fun `original log authorization still rejects workspace principal without raw permission`() {
        every { access.requireScope(11) } just Runs
        every { attachmentRepository.findAuthorizationFacts(22, 11) } returns
            availableAttachment().copy(attachmentType = AttachmentType.LOG)
        every { access.principal() } returns principal

        val error = assertThrows(ClientException::class.java) {
            service().authorize(
                AuthorizeFeedbackAttachmentCommand(11, 22, AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD)
            )
        }

        assertEquals("ORIGINAL_LOG_DOWNLOAD_RAW_PERMISSION_REQUIRED", error.reasonCode)
        verify(exactly = 0) { grants.issue(any()) }
        verify(exactly = 0) { auditRepository.append(any()) }
    }

    @Test
    fun `authorization audit failure is propagated after grant creation so transaction can roll back`() {
        every { access.requireScope(11) } just Runs
        every { attachmentRepository.findAuthorizationFacts(22, 11) } returns availableAttachment()
        every { access.principal() } returns principal
        every { grants.issue(any()) } returns grant()
        every { auditRepository.append(any()) } throws IllegalStateException("ATTACHMENT_ACCESS_AUDIT_FAILED")

        val error = assertThrows(IllegalStateException::class.java) {
            service().authorize(AuthorizeFeedbackAttachmentCommand(11, 22, AttachmentGrantPurpose.INLINE_VIEW))
        }

        assertEquals("ATTACHMENT_ACCESS_AUDIT_FAILED", error.message)
        verifyOrder { grants.issue(any()); auditRepository.append(any()) }
    }

    @Test
    fun `attachment list preserves repository order and applies every availability gate`() {
        val fieldPolicy = DataFieldPolicy(false, setOf("ATTACHMENT_TYPE", "AVAILABLE"), setOf("MIME_TYPE"))
        every { access.requireScope(11) } just Runs
        every { access.fieldPolicy() } returns fieldPolicy
        val queryTime = LocalDateTime.of(2026, 8, 29, 16, 30)
        every { attachmentRepository.findListFacts(11) } returns listOf(
            attachmentListFacts(22, queryTime, LocalDateTime.of(2026, 9, 29, 16, 30)),
            attachmentListFacts(23, queryTime, LocalDateTime.of(2026, 8, 29, 16, 30)).copy(
                attachmentType = AttachmentType.VIDEO,
                durationSeconds = 12,
                decryptionStatus = AttachmentDecryptionStatus.NOT_REQUIRED
            )
        )

        val actual = service().list(FeedbackAttachmentListQuery(11))

        assertEquals(listOf(22L, 23L), actual.map { it.id })
        assertEquals(true, actual[0].available)
        assertEquals(false, actual[1].available)
        assertEquals(12, actual[1].durationSeconds)
        actual.forEach { assertSame(fieldPolicy, it.fieldPolicy) }
        verify(exactly = 1) { access.fieldPolicy() }
        verify(exactly = 1) { attachmentRepository.findListFacts(11) }
    }

    @Test
    fun `attachment list rejects a module scope mismatch before attachment lookup`() {
        every { access.requireScope(11) } throws
            ClientException(ErrorCode.AUTH_DATA_PERMISSION_DENIED, "FEEDBACK_DATA_SCOPE_FORBIDDEN")

        assertThrows(ClientException::class.java) { service().list(FeedbackAttachmentListQuery(11)) }

        verify(exactly = 0) { access.fieldPolicy() }
        verify(exactly = 0) { attachmentRepository.findListFacts(any()) }
    }

    @Test
    fun `workspace permission is required before grant creation`() {
        every { access.requireScope(11) } throws
            ClientException(ErrorCode.AUTH_FORBIDDEN, "FEEDBACK_WORKSPACE_PERMISSION_REQUIRED")

        assertThrows(ClientException::class.java) {
            service().authorize(AuthorizeFeedbackAttachmentCommand(11, 22, AttachmentGrantPurpose.INLINE_VIEW))
        }
        verify(exactly = 0) { grants.issue(any()) }
    }

    @Test
    fun `module scope mismatch fails closed before attachment lookup`() {
        every { access.requireScope(11) } throws
            ClientException(ErrorCode.AUTH_DATA_PERMISSION_DENIED, "FEEDBACK_DATA_SCOPE_FORBIDDEN")

        assertThrows(ClientException::class.java) {
            service().authorize(AuthorizeFeedbackAttachmentCommand(11, 22, AttachmentGrantPurpose.INLINE_VIEW))
        }
        verify(exactly = 0) { attachmentRepository.findAuthorizationFacts(any<Long>(), any<Long>()) }
    }

    private fun service(): FeedbackAttachmentApplicationService {
        return FeedbackAttachmentApplicationServiceImpl(
        access = access,
        attachments = attachmentRepository,
        audits = auditRepository,
        grants = grants,
        previewPriority = previewPriority
    )
    }

    private fun availableAttachment() = AttachmentAuthorizationFacts(
        id = 22,
        sourceId = 11,
        attachmentType = AttachmentType.SCREENSHOT,
        securityStatus = AttachmentSecurityStatus.VERIFIED,
        integrityStatus = AttachmentIntegrityStatus.VERIFIED,
        decryptionStatus = AttachmentDecryptionStatus.DECRYPTED,
        malwareScanStatus = AttachmentMalwareScanStatus.CLEAN,
        contentReviewStatus = AttachmentContentReviewStatus.PASS,
        deletedAt = null
    )

    private fun grant(
        delivery: AttachmentDeliveryMode = AttachmentDeliveryMode.ONE_TIME_SECURE_STREAM,
        url: String = "/stream?grantId=${"g".repeat(43)}"
    ) = AttachmentAccessGrant(
        "g".repeat(43), LocalDateTime.of(2026, 8, 29, 16, 30), 300, delivery, url
    )

    private fun attachmentListFacts(
        id: Long,
        queryTime: LocalDateTime,
        retentionUntil: LocalDateTime?
    ) = AttachmentListFacts(
        id = id,
        sourceId = 11,
        attachmentType = AttachmentType.SCREENSHOT,
        mimeType = "image/png",
        fileSizeBytes = 2048,
        width = 1280,
        height = 720,
        durationSeconds = null,
        uploadStatus = AttachmentUploadStatus.VERIFIED,
        securityStatus = AttachmentSecurityStatus.VERIFIED,
        integrityStatus = AttachmentIntegrityStatus.VERIFIED,
        decryptionStatus = AttachmentDecryptionStatus.DECRYPTED,
        malwareScanStatus = AttachmentMalwareScanStatus.CLEAN,
        contentReviewStatus = AttachmentContentReviewStatus.PASS,
        sourceKind = AttachmentSourceKind.JIRA,
        retentionUntil = retentionUntil,
        createdAt = queryTime.minusMinutes(1),
        deletedAt = null,
        storedContentPresent = true,
        queryTime = queryTime
    )

}
