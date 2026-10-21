package com.feedback.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class WebSessionAuthenticationFilterTest {
    private val sessions = mockk<WebSessionStore>()
    private val users = mockk<WebUserDetailsService>()
    private val cookies = WebSessionCookieService(sessions, false, "Strict")
    private val filter = WebSessionAuthenticationFilter(sessions, users, cookies)

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    @Test
    fun `accepts an opaque session whose generation matches current user`() {
        val principal = principal(authVersion = 4)
        every { sessions.resolve(SESSION_ID) } returns StoredWebSession(7, 4)
        every { users.loadUserById(7) } returns principal
        val chain = mockk<FilterChain>(relaxed = true)
        filter.doFilter(request(SESSION_ID), MockHttpServletResponse(), chain)
        assertSame(principal, SecurityContextHolder.getContext().authentication?.principal)
        verify(exactly = 1) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `rejects a missing server-side session`() {
        every { sessions.resolve(SESSION_ID) } returns null
        val chain = mockk<FilterChain>(relaxed = true)
        filter.doFilter(request(SESSION_ID), MockHttpServletResponse(), chain)
        assertNull(SecurityContextHolder.getContext().authentication)
        verify(exactly = 0) { users.loadUserById(any()) }
    }

    @Test
    fun `rejects a session after current user generation advances`() {
        every { sessions.resolve(SESSION_ID) } returns StoredWebSession(7, 4)
        every { users.loadUserById(7) } returns principal(authVersion = 5)
        val chain = mockk<FilterChain>(relaxed = true)
        filter.doFilter(request(SESSION_ID), MockHttpServletResponse(), chain)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `does not accept a browser credential from an authorization header`() {
        val chain = mockk<FilterChain>(relaxed = true)
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer $SESSION_ID") }
        filter.doFilter(request, MockHttpServletResponse(), chain)
        assertNull(SecurityContextHolder.getContext().authentication)
        verify(exactly = 0) { sessions.resolve(any()) }
    }

    private fun request(sessionId: String) = MockHttpServletRequest().apply {
        setCookies(Cookie(WEB_SESSION_COOKIE, sessionId))
    }

    private fun principal(authVersion: Long) = WebPrincipal(
        7, "auditor", "unused", "Auditor", setOf("AUDITOR"), setOf("feedback:read"), authVersion
    )

    private companion object {
        const val SESSION_ID = "abcdefghijklmnopqrstuvwxyzABCDEFGH012345678"
    }
}
