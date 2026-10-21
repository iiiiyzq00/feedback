package com.feedback.config

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import com.feedback.manager.sdk.SdkRequestTelemetryRecorder
import org.mockito.Mockito

class ApiObservabilityFilterTest {
    @Test
    fun `adds correlation and server timing headers and records metric`() {
        val registry = SimpleMeterRegistry()
        val filter = ApiObservabilityFilter(registry,Mockito.mock(SdkRequestTelemetryRecorder::class.java))
        val request = MockHttpServletRequest("GET", "/api/v1/import/batches").apply { addHeader("X-Request-Id", "frontend-1234") }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, res -> (res as MockHttpServletResponse).status = 200 })
        assertEquals("frontend-1234", response.getHeader("X-Request-Id"))
        assertTrue(response.getHeader("Server-Timing")!!.startsWith("app;dur="))
        assertNotNull(registry.find("http.api.request.duration").timer())
    }

    @Test
    fun `rate limit response includes retry after`() {
        val filter = ApiObservabilityFilter(SimpleMeterRegistry(),Mockito.mock(SdkRequestTelemetryRecorder::class.java))
        val response = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("POST", "/api/v1/auth/login"), response, FilterChain { _, res -> (res as MockHttpServletResponse).status = 429 })
        assertEquals("3", response.getHeader("Retry-After"))
    }
}
