package com.feedback.service.attachment

import com.feedback.model.attachment.*

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.application.port.feedback.attachment.AttachmentAuthorizationFacts
import com.feedback.common.AttachmentGrantPurpose
import com.feedback.common.*
import com.feedback.repository.attachment.AttachmentGrantRepository
import com.feedback.repository.attachment.SecureAttachmentRepository
import com.feedback.security.attachment.FeedbackAttachmentAccessPort
import com.feedback.manager.attachment.AttachmentGrantTransactionPort
import com.feedback.security.ContentVisibility
import com.feedback.security.WebPrincipal
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.mockk.just
import io.mockk.Runs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import com.feedback.service.impl.attachment.AttachmentSecureStreamServiceImpl

class AttachmentGrantConsumerTest {
    private val grants = mockk<AttachmentGrantRepository>()
    private val attachments = mockk<SecureAttachmentRepository>()
    private val access = mockk<FeedbackAttachmentAccessPort>()
    private val transactions = immediateTransactions()
    private val token = "a".repeat(43)

    @Test
    fun `rechecks attachment safety before consuming grant`() {
        every { access.requireScope(11) } just Runs
        every { access.principal() } returns principal()
        every { attachments.findAuthorizationFacts(22, 11) } returns
            facts(malwareScanStatus = AttachmentMalwareScanStatus.PENDING)

        val error = assertThrows(ClientException::class.java) {
            consumer().consume(11, 22, token, AttachmentGrantPurpose.INLINE_VIEW)
        }

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode)
        verify(exactly = 0) { grants.lockConsumableId(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { grants.markConsumed(any()) }
    }

    @Test
    fun `passes requested purpose to the locked grant lookup`() {
        every { access.requireScope(11) } just Runs
        every { access.principal() } returns principal(permissions = setOf(ContentVisibility.RAW_PERMISSION.code))
        every { attachments.findAuthorizationFacts(22, 11) } returns facts(attachmentType = AttachmentType.LOG)
        every {
            grants.lockConsumableId(any(), 22, 11, 7, AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD)
        } returns 91
        every { grants.markConsumed(91) } returns true

        consumer().consume(11, 22, token, AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD)

        verify(exactly = 1) {
            grants.lockConsumableId(any(), 22, 11, 7, AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD)
        }
        verifyOrder {
            access.requireScope(11)
            access.principal()
            attachments.findAuthorizationFacts(22, 11)
            grants.lockConsumableId(any(), 22, 11, 7, AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD)
            grants.markConsumed(91)
        }
    }

    @Test
    fun `product role without raw permission cannot consume original log grant`() {
        every { access.requireScope(11) } just Runs
        every { access.principal() } returns principal(roles = setOf("PHASE_ONE_PRODUCT"))
        every { attachments.findAuthorizationFacts(22, 11) } returns facts(attachmentType = AttachmentType.LOG)

        assertThrows(ClientException::class.java) {
            consumer().consume(11, 22, token, AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD)
        }

        verify(exactly = 0) { grants.lockConsumableId(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `revoked data scope fails before attachment grant and storage access`() {
        val provider = mockk<AttachmentContentProvider>()
        every { access.requireScope(11) } throws
            ClientException(ErrorCode.AUTH_DATA_PERMISSION_DENIED, "DATA_SCOPE_ACCESS_DENIED")
        val consumer = consumer()
        val service = AttachmentSecureStreamServiceImpl(grants, provider, consumer, 300)

        val error = assertThrows(ClientException::class.java) {
            service.consume(11, 22, token)
        }

        assertEquals("DATA_SCOPE_ACCESS_DENIED", error.reasonCode)
        verify(exactly = 0) { access.principal() }
        verify(exactly = 0) { attachments.findAuthorizationFacts(any<Long>(), any<Long>()) }
        verify(exactly = 0) { grants.lockConsumableId(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { grants.markConsumed(any()) }
        verify(exactly = 0) { provider.open(any()) }
        verify(exactly = 0) { provider.openLogPreview(any()) }
    }

    private fun consumer() = AttachmentGrantConsumer(grants, attachments, access, transactions)

    private fun principal(
        roles: Set<String> = setOf("PHASE_ONE_PRODUCT"),
        permissions: Set<String> = emptySet()
    ) = WebPrincipal(7, "product", "", "Product", roles, permissions, authProvider = "SSO")

    private fun facts(
        attachmentType: AttachmentType = AttachmentType.IMAGE,
        malwareScanStatus: AttachmentMalwareScanStatus = AttachmentMalwareScanStatus.CLEAN
    ) = AttachmentAuthorizationFacts(
        id = 22,
        sourceId = 11,
        attachmentType = attachmentType,
        securityStatus = AttachmentSecurityStatus.VERIFIED,
        integrityStatus = AttachmentIntegrityStatus.VERIFIED,
        decryptionStatus = AttachmentDecryptionStatus.NOT_REQUIRED,
        malwareScanStatus = malwareScanStatus,
        contentReviewStatus = AttachmentContentReviewStatus.PASS,
        deletedAt = null
    )

    private fun immediateTransactions() = AttachmentGrantTransactionPort { action -> action() }
}
