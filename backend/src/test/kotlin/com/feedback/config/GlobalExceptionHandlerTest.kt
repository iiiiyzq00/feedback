package com.feedback.config

import com.feedback.common.ApiErrorDetails
import com.feedback.common.ApiResponse
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.RemoteException
import com.feedback.common.exception.ServiceException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException

class GlobalExceptionHandlerTest {
    private val meters = SimpleMeterRegistry()
    private val handler = GlobalExceptionHandler(meters)

    @Test
    fun `conflict is actionable and not retryable automatically`() {
        val response = handler.serviceException(
            ServiceException(ErrorCode.CLUSTER_REVIEW_CONFLICT, "CLUSTER_REVIEW_VERSION_CONFLICT"),
            MockHttpServletRequest("POST", "/api/v1/cluster/7/confirm")
        )
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(8001, response.body!!.code)
        assertEquals(false, response.body!!.retryable)
        assertEquals("CLUSTER_REVIEW_VERSION_CONFLICT", response.body!!.details?.reasonCode)
    }

    @Test
    fun `phase one conflicts use only their authoritative error codes`() {
        listOf(
            ErrorCode.CLUSTER_REVIEW_CONFLICT to 8001,
            ErrorCode.STATION_IDEMPOTENCY_CONFLICT to 8004,
            ErrorCode.APPLICATION_IDENTITY_CONFLICT to 8005
        ).forEach { (code, expected) ->
            val response = handler.serviceException(
                ServiceException(code, "FEEDBACK_CONFLICT"),
                MockHttpServletRequest("POST", "/api/v1/feedback/conflict")
            )
            assertEquals(HttpStatus.CONFLICT, response.statusCode)
            assertEquals(expected, response.body!!.code)
            assertEquals(code.message, response.body!!.message)
            assertEquals(false, response.body!!.retryable)
            assertEquals("FEEDBACK_CONFLICT", response.body!!.details?.reasonCode)
        }
    }

    @Test
    fun `typed exceptions preserve their public contract and record their owning layer`() {
        val request = MockHttpServletRequest("GET", "/api/v1/feedback/test")
        val cases = listOf(
            Triple(
                handler.clientException(ClientException(ErrorCode.SDK_SIGNATURE_INVALID, "SDK_SIGNATURE_INVALID"), request),
                ErrorCode.SDK_SIGNATURE_INVALID,
                "client"
            ),
            Triple(
                handler.serviceException(ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "FEEDBACK_READ_UNAVAILABLE"), request),
                ErrorCode.FEEDBACK_READ_UNAVAILABLE,
                "service"
            ),
            Triple(
                handler.remoteException(RemoteException(ErrorCode.JIRA_UNAVAILABLE, "JIRA_UNAVAILABLE"), request),
                ErrorCode.JIRA_UNAVAILABLE,
                "remote"
            )
        )

        cases.forEach { (response, errorCode, layer) ->
            assertEquals(errorCode.httpStatus, response.statusCode)
            assertEquals(errorCode.code, response.body!!.code)
            assertEquals(errorCode.message, response.body!!.message)
            assertEquals(errorCode.retryable, response.body!!.retryable)
            assertEquals(errorCode.name, response.body!!.details?.reasonCode)
            assertEquals(
                1.0,
                meters.get("feedback.exceptions")
                    .tags(
                        "layer", layer,
                        "code", errorCode.name,
                        "http_status", errorCode.httpStatus.value().toString()
                    )
                    .counter().count()
            )
        }
    }

    @Test
    fun `retired conflict codes are absent from the public error catalog`() {
        assertTrue(ErrorCode.entries.none { it.code in setOf(4091, 4094, 4095) })
    }

    @Test
    fun `public error details use the closed named contract`() {
        assertEquals(ApiErrorDetails::class.java, ApiResponse::class.java.getDeclaredField("details").type)
    }

    @Test
    fun `public details discard unknown and unsafe values`() {
        val response = handler.clientException(
            ClientException(
                ErrorCode.BAD_REQUEST,
                details = mapOf(
                    "reasonCode" to "unsafe reason",
                    "secret" to "must-not-leak",
                    "upstreamStatus" to 999,
                    "retryAfterSeconds" to 0,
                    "fields" to mapOf("safeName" to object {
                        override fun toString() = "secret-object-value"
                    })
                )
            ),
            MockHttpServletRequest("GET", "/api/v1/feedback/test")
        )

        assertNull(response.body!!.details)
    }

    @Test
    fun `internal error is retryable without exposing exception`() {
        val request = MockHttpServletRequest("GET", "/api/v1/test").apply { setAttribute("requestId", "request-5678") }
        val response = handler.general(IllegalStateException("database password must not leak"), request)
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals(true, response.body!!.retryable)
        assertFalse(response.body!!.message.contains("password"))
    }

    @Test
    fun `response status is preserved with a safe reason code`() {
        val request = MockHttpServletRequest("GET", "/api/v1/feedback/1/attachments/2/stream")
        val response = handler.responseStatus(ResponseStatusException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_AVAILABLE"), request)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("ATTACHMENT_NOT_AVAILABLE", response.body!!.details?.reasonCode)
    }
}
