package com.feedback.security.attachment

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.security.FeedbackAccessGuard
import com.feedback.security.WebPrincipal
import com.feedback.service.auth.DataPermissionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Component

class FeedbackAttachmentAccessAdapterTest {
    private val feedbackAccess = mockk<FeedbackAccessGuard>()
    private val permissions = mockk<DataPermissionService>()
    private val principals = mockk<CurrentWebPrincipalProvider>()
    private val adapter = FeedbackAttachmentAccessAdapter(feedbackAccess, permissions, principals)

    @Test
    fun `adapter is a Spring component and delegates each port operation to the authoritative boundary`() {
        val policy = DataFieldPolicy(true, emptySet(), emptySet())
        val principal = mockk<WebPrincipal>()
        every { feedbackAccess.require(11) } returns mockk()
        every { permissions.fieldPolicy() } returns policy
        every { principals.require() } returns principal

        adapter.requireScope(11)

        assertSame(policy, adapter.fieldPolicy())
        assertSame(principal, adapter.principal())
        verify(exactly = 1) { feedbackAccess.require(11) }
        verify(exactly = 1) { permissions.fieldPolicy() }
        verify(exactly = 1) { principals.require() }
        assertSame(Component::class.java, FeedbackAttachmentAccessAdapter::class.java
            .getAnnotation(Component::class.java).annotationClass.java)
    }

    @Test
    fun `scope denial propagates without consulting weaker attachment context`() {
        every { feedbackAccess.require(11) } throws
            ClientException(ErrorCode.AUTH_DATA_PERMISSION_DENIED, "DATA_SCOPE_ACCESS_DENIED")

        assertThrows(ClientException::class.java) { adapter.requireScope(11) }

        verify(exactly = 0) { permissions.fieldPolicy() }
        verify(exactly = 0) { principals.require() }
    }
}
