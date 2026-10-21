package com.feedback.controller.auth

import com.feedback.dto.auth.LoginRequest
import com.feedback.security.LoginProtection
import com.feedback.security.WEB_SESSION_COOKIE
import com.feedback.security.WebPrincipal
import com.feedback.security.WebSessionCookieService
import com.feedback.security.WebSessionStore
import com.feedback.service.auth.AuthSessionLifecycleService
import com.feedback.service.auth.AuthRegistrationService
import com.feedback.model.auth.AuthenticatedUserResult
import com.feedback.service.auth.FeishuSsoService
import com.feedback.service.auth.LocalAuthenticationService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.web.csrf.CsrfToken

/** 冻结本地登录、Cookie 安全属性及会话恢复时 CSRF 初始化的 HTTP 边界行为。 */
class AuthControllerSessionCookieTest {
    @Test
    fun `login returns user data without exposing credentials and establishes opaque HttpOnly cookie`() {
        val principal = WebPrincipal(7, "auditor", "hash", "Auditor", setOf("AUDITOR"), setOf("feedback:read"))
        val localAuthentication = mockk<LocalAuthenticationService>()
        val sessions = mockk<AuthSessionLifecycleService>()
        val protection = mockk<LoginProtection>()
        every { protection.assertAllowed(any(), any()) } just runs
        every { protection.resetAfterSuccess("auditor") } just runs
        every { localAuthentication.authenticate("auditor", "password", "127.0.0.1") } returns
            AuthenticatedUserResult(
                principal.userId,
                principal.username,
                principal.displayName,
                principal.roles,
                principal.permissions,
                principal.authVersion,
                principal.authProvider,
            )
        val controller = AuthController(
            localAuthentication = localAuthentication,
            registrations = mockk<AuthRegistrationService>(),
            sessions = sessions,
            loginProtection = protection,
            feishu = mockk<FeishuSsoService>(),
            sessionCookies = WebSessionCookieService(mockk<WebSessionStore> {
                every { create(principal.userId, principal.authVersion) } returns "opaque-session-id"
                every { ttlSeconds } returns 3600
            }, true, "Strict")
        )
        val response = MockHttpServletResponse()
        val csrf = mockk<CsrfToken>()
        every { csrf.token } returns "csrf-token"

        val result = controller.login(
            LoginRequest("auditor", "password"),
            MockHttpServletRequest().apply { remoteAddr = "127.0.0.1" },
            response,
            csrf
        )

        assertNotNull(result.data?.get("user"))
        assertFalse(result.data?.containsKey("token") == true)
        val setCookie = response.getHeader("Set-Cookie").orEmpty()
        assertTrue(setCookie.startsWith("$WEB_SESSION_COOKIE="))
        assertTrue(setCookie.contains("HttpOnly"))
    }

    @Test
    fun `me resolves csrf token so a restored session receives readable csrf cookie`() {
        val principal = WebPrincipal(7, "auditor", "hash", "Auditor", setOf("AUDITOR"), setOf("feedback:read"))
        val csrf = mockk<CsrfToken>()
        every { csrf.token } returns "csrf-token"
        val controller = AuthController(
            localAuthentication = mockk(),
            registrations = mockk(),
            sessions = mockk(),
            loginProtection = mockk(),
            feishu = mockk(),
            sessionCookies = mockk()
        )

        val result = controller.me(principal, csrf)

        assertNotNull(result.data?.get("username"))
        verify(exactly = 1) { csrf.token }
    }
}
