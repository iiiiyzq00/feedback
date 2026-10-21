package com.feedback.service.query

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.repository.query.ChannelAcceptanceRepository
import com.feedback.repository.query.ProductFeedbackRepository
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class PhaseOneExceptionBehaviorTest {
    @AfterEach
    fun clearAuthentication() = SecurityContextHolder.clearContext()

    @Test
    fun `missing web principal exposes the unified unauthorized contract`() {
        val error = assertThrows(ClientException::class.java) { CurrentWebPrincipalProvider().require() }

        assertEquals(ErrorCode.AUTH_UNAUTHORIZED, error.errorCode)
        assertEquals("AUTHENTICATION_REQUIRED", error.reasonCode)
    }

    @Test
    fun `channel validation exposes a typed bad request before persistence`() {
        authenticate(setOf("ADMIN"))
        val repository = mockk<ChannelAcceptanceRepository>()
        val service = ChannelAcceptanceService(repository, mockk<ObjectMapper>())

        val error = assertThrows(ClientException::class.java) {
            service.accept("UNKNOWN", ChannelAcceptanceInput("PASSED", "有效说明"))
        }

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("CHANNEL_INVALID", error.reasonCode)
        verify(exactly = 0) { repository.accept(any(), any(), any(), any()) }
    }

    @Test
    fun `missing product feedback exposes the unified not found contract`() {
        authenticate(setOf("ADMIN"))
        val repository = mockk<ProductFeedbackRepository>()
        every { repository.find(77) } returns null
        val service = ProductFeedbackRequestService(
            repository,
            mockk<ObjectMapper>(),
            mockk<DataPermissionService>()
        )

        val error = assertThrows(ClientException::class.java) { service.detail(77) }

        assertEquals(ErrorCode.NOT_FOUND, error.errorCode)
        assertEquals("PRODUCT_FEEDBACK_NOT_FOUND", error.reasonCode)
    }

    private fun authenticate(roles: Set<String>) {
        val principal = WebPrincipal(9, "tester", "", "测试用户", roles, emptySet(), authProvider = "SSO")
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated(principal, null, emptyList())
    }
}
