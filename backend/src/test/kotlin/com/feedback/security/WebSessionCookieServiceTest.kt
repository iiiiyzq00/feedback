package com.feedback.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class WebSessionCookieServiceTest {
    private val sessions = mockk<WebSessionStore>()
    private val principal = WebPrincipal(7, "auditor", "unused", "Auditor", setOf("AUDITOR"), emptySet())

    @Test
    fun `establishes an HttpOnly host-only strict session cookie`() {
        val response = MockHttpServletResponse()

        every { sessions.create(principal.userId, principal.authVersion) } returns "opaque-session-id"
        every { sessions.ttlSeconds } returns 3600
        WebSessionCookieService(sessions, true, "Strict").establish(
            MockHttpServletRequest(),
            response,
            principal.userId,
            principal.authVersion,
        )

        val header = response.getHeader(HttpHeaders.SET_COOKIE).orEmpty()
        assertTrue(header.startsWith("$WEB_SESSION_COOKIE="))
        assertTrue(header.contains("HttpOnly"))
        assertTrue(header.contains("Secure"))
        assertTrue(header.contains("SameSite=Strict"))
        assertTrue(header.contains("Path=/"))
        assertFalse(header.contains("Domain="))
    }

    @Test
    fun `reads only the dedicated opaque session cookie`() {
        val request = MockHttpServletRequest().apply {
            setCookies(Cookie("other", "ignored"), Cookie(WEB_SESSION_COOKIE, "signed-value"))
        }

        assertEquals("signed-value", WebSessionCookieService(sessions, false, "Strict").sessionId(request))
    }

    @Test
    fun `logout expires both session and CSRF cookies`() {
        val response = MockHttpServletResponse()

        every { sessions.revoke("opaque-session-id") } returns Unit
        val request = MockHttpServletRequest().apply {
            setCookies(Cookie(WEB_SESSION_COOKIE, "opaque-session-id"))
        }
        WebSessionCookieService(sessions, true, "Strict").clear(request, response)

        val headers = response.getHeaders(HttpHeaders.SET_COOKIE)
        assertEquals(2, headers.size)
        assertTrue(headers.all { it.contains("Max-Age=0") })
        assertTrue(headers.any { it.startsWith("$WEB_SESSION_COOKIE=") && it.contains("HttpOnly") })
        assertTrue(headers.any { it.startsWith("XSRF-TOKEN=") && !it.contains("HttpOnly") })
        verify(exactly = 1) { sessions.revoke("opaque-session-id") }
    }
}
