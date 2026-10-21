package com.feedback.common

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class ApiResponseTest {
    @AfterEach fun cleanup() = MDC.clear()

    @Test
    fun `response carries request id and retry semantics`() {
        MDC.put("requestId", "request-1234")
        val response = ApiResponse.error(ErrorCode.INTERNAL_ERROR)
        assertEquals("request-1234", response.requestId)
        assertEquals(true, response.retryable)
        assertNotNull(response.timestamp)
    }
}
