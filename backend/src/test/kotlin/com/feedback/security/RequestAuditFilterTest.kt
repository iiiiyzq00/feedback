package com.feedback.security

import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class RequestAuditFilterTest {
    @AfterEach fun clear()=SecurityContextHolder.clearContext()

    @Test fun `authenticated API request is durably queued without synchronous chain append`() {
        val audits=mockk<BusinessAuditService>()
        every { audits.enqueue(any()) } returns Unit
        val principal=WebPrincipal(9,"operator","unused","Operator",setOf("OPERATOR"),setOf("dashboard:read"),3)
        SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(principal,null,principal.authorities)
        val request=MockHttpServletRequest("GET","/api/v1/dashboard/overview")
        val response=MockHttpServletResponse()
        val chain=mockk<FilterChain>(relaxed=true)

        RequestAuditFilter(audits).doFilter(request,response,chain)

        verify(exactly=1){audits.enqueue(match<BusinessAuditEvent>{it.actor==9L&&it.operation=="HTTP_GET"&&it.auditClass=="QUERY"})}
        verify(exactly=0){audits.append(any())}
    }

    @ParameterizedTest
    @ValueSource(strings=["/api/v1/admin/users","/api/v1/admin/configs","/api/v1/admin/masking-rules","/api/v1/admin/masking-rules/ner-allowlist"])
    fun `admin reads are always classified as queries`(path:String) {
        val event = audit("GET", path)
        assertEquals("HTTP_GET", event.operation)
        assertEquals("QUERY", event.auditClass)
    }

    @Test fun `admin mutations retain their business operation`() {
        assertEquals("CONFIG_UPDATE", audit("PUT", "/api/v1/admin/configs/sdk.rate-limit").operation)
        assertEquals("MASKING_RULE_EDIT", audit("PUT", "/api/v1/admin/masking-rules/1").operation)
        assertEquals("NER_WHITELIST_EDIT", audit("POST", "/api/v1/admin/masking-rules/ner-allowlist").operation)
        assertEquals("DISABLE_USER", audit("PUT", "/api/v1/admin/users/9/disable").operation)
        assertEquals("MASKING_TEST", audit("POST", "/api/v1/admin/masking-rules/test").operation)
        assertEquals("QUERY", audit("POST", "/api/v1/admin/masking-rules/test").auditClass)
    }

    private fun audit(method:String,path:String):BusinessAuditEvent {
        val audits=mockk<BusinessAuditService>()
        val captured=io.mockk.slot<BusinessAuditEvent>()
        every { audits.enqueue(capture(captured)) } returns Unit
        val principal=WebPrincipal(9,"operator","unused","Operator",setOf("ADMIN"),setOf("admin:manage"),3)
        SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(principal,null,principal.authorities)
        RequestAuditFilter(audits).doFilter(MockHttpServletRequest(method,path),MockHttpServletResponse(),mockk(relaxed=true))
        return captured.captured
    }
}
