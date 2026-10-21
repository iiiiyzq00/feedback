package com.feedback.controller.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.config.DynamicConfigGovernanceService
import com.feedback.config.DynamicConfigItemResponse
import com.feedback.config.GlobalExceptionHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.LocalDateTime

class DynamicConfigAdminControllerTest {
    private val service = mockk<DynamicConfigGovernanceService>()
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val mvc = MockMvcBuilders.standaloneSetup(DynamicConfigAdminController(service))
        .setControllerAdvice(GlobalExceptionHandler(SimpleMeterRegistry()))
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .build()

    @Test
    fun `current endpoint preserves the frontend field contract`() {
        every { service.current() } returns listOf(
            DynamicConfigItemResponse(
                "sdk.rate-limit", "v3", mapper.readTree("100"), "SDK 每分钟请求上限", "容量调整",
                LocalDateTime.parse("2026-08-30T10:00:00"), "INTEGER", BigDecimal.ONE,
                BigDecimal("1000"), true, "SDK"
            )
        )

        mvc.get("/api/v1/admin/configs").andExpect {
            status { isOk() }
            jsonPath("$.data[0].configKey") { value("sdk.rate-limit") }
            jsonPath("$.data[0].version") { value("v3") }
            jsonPath("$.data[0].valueType") { value("INTEGER") }
            jsonPath("$.data[0].hotReload") { value(true) }
            jsonPath("$.data[0].ownerModule") { value("SDK") }
        }
    }

    @Test
    fun `change set rejects missing expected version before calling the service`() {
        mvc.post("/api/v1/admin/configs/change-set") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"提高接入容量","changes":[{"key":"sdk.rate-limit","value":120}]}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `history rejects an out of range page contract`() {
        mvc.get("/api/v1/admin/configs/sdk.rate-limit/history") {
            param("page", "0")
            param("size", "20")
        }.andExpect { status { isBadRequest() } }
    }
}
