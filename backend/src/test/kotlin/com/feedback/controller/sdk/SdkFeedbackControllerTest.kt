package com.feedback.controller.sdk

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.config.JacksonConfig
import com.feedback.service.sdk.SdkAttachmentService
import com.feedback.service.sdk.SdkConfigService
import com.feedback.service.sdk.SdkIngestService
import com.feedback.service.sdk.SdkTelemetryService
import io.mockk.mockk
import io.mockk.verify
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class SdkFeedbackControllerTest {
    private val ingest = mockk<SdkIngestService>(relaxed = true)
    private val controller = SdkFeedbackController(
        ingest,
        mockk<SdkAttachmentService>(),
        mockk<SdkTelemetryService>(),
        mockk<SdkConfigService>(),
        JacksonConfig().objectMapper(),
        Validation.buildDefaultValidatorFactory().validator
    )

    @Test
    fun `missing sdk header throws typed error with stable message`() {
        val request = MockHttpServletRequest("POST", "/api/v1/sdk/feedback")

        val error = assertThrows(ClientException::class.java) {
            controller.submitFeedback("{}", request)
        }

        assertEquals(ErrorCode.SDK_SIGNATURE_INVALID, error.errorCode)
        assertEquals(ErrorCode.SDK_SIGNATURE_INVALID.message, error.message)
        assertEquals("SDK_APP_KEY_MISSING", error.reasonCode)
        assertEquals("SDK_APP_KEY_MISSING", request.getAttribute("sdkErrorCode"))
        verify(exactly = 0) { ingest.ingestFeedback(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `manual dto validation throws typed bad request instead of assembling response`() {
        val request = signedRequest()
        val body = """{
            "client_feedback_id":"",
            "package_name":"",
            "app_version":null,
            "function_module":null,
            "issue_type":null,
            "source_event_time":"",
            "os_version":null,
            "network_type":null,
            "trigger_type":null,
            "encrypted_payload":{
              "key_id":"","algorithm":"","encrypted_data_key":"","nonce":"","ciphertext":"","tag":""
            }
        }""".trimIndent()

        val error = assertThrows(ClientException::class.java) {
            controller.submitFeedback(body, request)
        }

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals(ErrorCode.BAD_REQUEST.message, error.message)
        assertEquals("SDK_VALIDATION_FAILED", error.reasonCode)
        assertEquals("SDK_VALIDATION_FAILED", request.getAttribute("sdkErrorCode"))
        verify(exactly = 0) { ingest.ingestFeedback(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun signedRequest() = MockHttpServletRequest("POST", "/api/v1/sdk/feedback").apply {
        addHeader("X-App-Key", "app")
        addHeader("X-Signature", "signature")
        addHeader("X-Timestamp", "1")
        addHeader("X-Nonce", "nonce")
    }
}
