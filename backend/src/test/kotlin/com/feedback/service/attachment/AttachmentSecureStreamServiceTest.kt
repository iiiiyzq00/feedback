package com.feedback.service.attachment

import com.feedback.model.attachment.*

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import com.feedback.common.exception.ServiceException
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.security.WebPrincipal
import com.feedback.repository.attachment.AttachmentGrantRepository
import com.feedback.common.AttachmentGrantPurpose
import com.feedback.common.AttachmentAuthorizationAction
import com.feedback.common.AttachmentType
import com.feedback.application.port.feedback.attachment.CreateAttachmentGrantCommand
import com.feedback.model.attachment.ConsumedAttachmentGrant
import com.feedback.model.attachment.IssueAttachmentGrantCommand
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import com.feedback.service.impl.attachment.AttachmentSecureStreamServiceImpl

class AttachmentSecureStreamServiceTest {
    private val grants = mockk<AttachmentGrantRepository>()
    private val provider = mockk<AttachmentContentProvider>()
    private val consumer = mockk<AttachmentGrantConsumer>()
    private val principal = WebPrincipal(7, "operator", "hash", "Operator", setOf("OPERATOR"), emptySet())

    @AfterEach fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `authorization stores only token hash and returns one-time URL`() {
        val command = slot<CreateAttachmentGrantCommand>()
        every { grants.create(capture(command)) } just Runs
        val result = AttachmentSecureStreamServiceImpl(grants, provider, consumer, 300)
            .issue(IssueAttachmentGrantCommand(
                11,
                22,
                AttachmentAuthorizationAction.DIRECT_VIEW,
                principal,
                AttachmentGrantPurpose.INLINE_VIEW
            ))
        val token = result.grantId

        assertEquals(AttachmentDeliveryMode.ONE_TIME_SECURE_STREAM, result.delivery)
        assertEquals(32, command.captured.fingerprint.size)
        assertFalse(command.captured.fingerprint.contentEquals(token.toByteArray()))
        assertFalse(command.captured.accessReason.contains(AttachmentAuthorizationAction.DIRECT_VIEW.code))
        assertEquals(
            true,
            command.captured.accessReason.matches(
                Regex("sha256:[a-f0-9]{64};length:${AttachmentAuthorizationAction.DIRECT_VIEW.code.length}")
            )
        )
    }

    @Test
    fun `derived preview grant is purpose prefixed and cannot be confused with original`() {
        val command = slot<CreateAttachmentGrantCommand>()
        every { grants.create(capture(command)) } just Runs

        val result = AttachmentSecureStreamServiceImpl(grants, provider, consumer, 300).issue(
            IssueAttachmentGrantCommand(
                11,
                22,
                AttachmentAuthorizationAction.DERIVED_PREVIEW,
                principal,
                AttachmentGrantPurpose.DERIVED_PREVIEW
            )
        )

        assertEquals(AttachmentDeliveryMode.ONE_TIME_DERIVED_PREVIEW, result.delivery)
        assertTrue(command.captured.accessReason.startsWith("DERIVED_PREVIEW|sha256:"))
        assertTrue(result.downloadUrl.contains("/preview?grantId="))
    }

    @Test
    fun `derived preview is consumed before private preview object opens`() {
        every { consumer.consume(11, 22, "p".repeat(43), AttachmentGrantPurpose.DERIVED_PREVIEW) } returns
            ConsumedAttachmentGrant(AttachmentType.IMAGE, principal)
        val expected = SecureAttachmentContent(
            AttachmentContentSource { byteArrayOf(1).inputStream() }, "image/jpeg", "preview.jpg", 1
        )
        every { provider.openMediaPreview(22) } returns expected

        val actual = AttachmentSecureStreamServiceImpl(grants, provider, consumer, 300)
            .consumeMediaPreview(11, 22, "p".repeat(43))

        assertEquals(expected, actual)
        verifyOrder {
            consumer.consume(11, 22, "p".repeat(43), AttachmentGrantPurpose.DERIVED_PREVIEW)
            provider.openMediaPreview(22)
        }
        verify(exactly = 0) { provider.open(any()) }
    }

    @Test
    fun `grant is bound to authenticated user and consumed before provider opens`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        every { consumer.consume(11, 22, "a".repeat(43), AttachmentGrantPurpose.INLINE_VIEW) } returns
            ConsumedAttachmentGrant(AttachmentType.IMAGE, principal)
        val expected = SecureAttachmentContent(AttachmentContentSource { byteArrayOf(1).inputStream() }, "image/jpeg", "safe.jpg")
        every { provider.open(22) } returns expected

        val actual = AttachmentSecureStreamServiceImpl(grants, provider, consumer, 300).consume(11, 22, "a".repeat(43))
        assertEquals(expected, actual)
        verifyOrder {
            consumer.consume(11, 22, "a".repeat(43), AttachmentGrantPurpose.INLINE_VIEW)
            provider.open(22)
        }
    }

    @Test
    fun `expired consumed or foreign grant is rejected without opening provider`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        every { consumer.consume(11, 22, "b".repeat(43), AttachmentGrantPurpose.INLINE_VIEW) } throws
            ClientException(ErrorCode.ATTACHMENT_ACCESS_CONFLICT, "ATTACHMENT_GRANT_INVALID_EXPIRED_OR_CONSUMED")

        assertThrows(ClientException::class.java) {
            AttachmentSecureStreamServiceImpl(grants, provider, consumer, 300).consume(11, 22, "b".repeat(43))
        }
        verify(exactly = 0) { provider.open(any()) }
    }

    @Test
    fun `grant lost to a concurrent consumer is rejected without opening provider`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        every { consumer.consume(11, 22, "c".repeat(43), AttachmentGrantPurpose.INLINE_VIEW) } throws
            ClientException(ErrorCode.ATTACHMENT_ACCESS_CONFLICT, "ATTACHMENT_GRANT_INVALID_EXPIRED_OR_CONSUMED")

        assertThrows(ClientException::class.java) {
            AttachmentSecureStreamServiceImpl(grants, provider, consumer, 300).consume(11, 22, "c".repeat(43))
        }
        verify(exactly = 0) { provider.open(any()) }
    }

    @Test
    fun `log view reads only precomputed preview and never opens original attachment`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        every { consumer.consume(11, 22, "d".repeat(43), AttachmentGrantPurpose.INLINE_VIEW) } returns
            ConsumedAttachmentGrant(AttachmentType.LOG, principal)
        every { provider.openLogPreview(22) } returns SecureAttachmentContent(
            AttachmentContentSource { "line one\nline two".byteInputStream() },
            "text/plain;charset=UTF-8",
            "attachment-22-preview.log",
            17
        )

        val content = AttachmentSecureStreamServiceImpl(grants, provider, consumer, 300)
            .consumeForView(11, 22, "d".repeat(43), 4 * 1024)

        assertTrue(content.openStream().bufferedReader().readText().contains("line one"))
        verify(exactly = 1) { provider.openLogPreview(22) }
        verify(exactly = 0) { provider.open(any()) }
    }

    @Test
    fun `log view fails fast while precomputed preview is not ready`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        every { consumer.consume(11, 22, "e".repeat(43), AttachmentGrantPurpose.INLINE_VIEW) } returns
            ConsumedAttachmentGrant(AttachmentType.LOG, principal)
        every { provider.openLogPreview(22) } returns null

        val error = assertThrows(ServiceException::class.java) {
            AttachmentSecureStreamServiceImpl(grants, provider, consumer, 300)
                .consumeForView(11, 22, "e".repeat(43), 4 * 1024)
        }

        assertEquals(409, error.errorCode.httpStatus.value())
        verify(exactly = 0) { provider.open(any()) }
    }
}
