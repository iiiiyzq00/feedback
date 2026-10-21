package com.feedback.controller.station

import com.feedback.dto.station.StationAcceptResult
import com.feedback.service.station.StationFeedbackIngestService
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class StationFeedbackControllerTest {
    private val service = mockk<StationFeedbackIngestService>()
    private val mapper = ObjectMapper()
    private val mvc = MockMvcBuilders.standaloneSetup(StationFeedbackController(service)).build()

    @Test
    fun `V1 preserves legacy success response and exposes receipt header`() {
        every { service.accept(any(), any(), any(), any()) } returns StationAcceptResult("receipt-1", 1)
        mvc.post("/v1/feedback") {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(validRequest())
        }.andExpect {
            status { isOk() }
            header { string("X-Feedback-Receipt-Id", "receipt-1") }
            jsonPath("$.code") { value(0) }
            jsonPath("$.msg") { value("ok") }
        }
    }

    @Test
    fun `V2 selects new contract version`() {
        every { service.accept(any(), match { it.name == "V2" }, any(), any()) } returns StationAcceptResult("receipt-2", 2)
        mvc.post("/v1/feedback/new") {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(validRequest() + ("phone" to "ciphertext"))
        }.andExpect { status { isOk() }; jsonPath("$.code") { value(0) } }
    }

    private fun validRequest() = mapOf(
        "productId" to "product-1", "appVersion" to "1.0", "title" to "问题",
        "content" to "课程无法打开", "platform" to "ANDROID", "type" to "BUG",
        "channel" to "APP", "feedbackAttributeRecords" to emptyList<Map<String, Any>>()
    )
}
