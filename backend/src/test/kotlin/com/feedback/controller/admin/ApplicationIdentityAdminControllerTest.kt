package com.feedback.controller.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.config.GlobalExceptionHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.feedback.model.applicationidentity.ApplicationAliasRebindResult
import com.feedback.service.applicationidentity.ApplicationIdentityAdminService
import com.feedback.model.applicationidentity.ApplicationIdentityShadowBatchResult
import com.feedback.service.applicationidentity.ApplicationIdentityShadowService
import com.feedback.model.applicationidentity.ApplicationReviewedBindingResult
import com.feedback.model.applicationidentity.ApplicationReviewedBindingDecision
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ApplicationIdentityAdminControllerTest {
    private val admin = mockk<ApplicationIdentityAdminService>()
    private val shadow = mockk<ApplicationIdentityShadowService>()
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val mvc = MockMvcBuilders.standaloneSetup(ApplicationIdentityAdminController(admin, shadow))
        .setControllerAdvice(GlobalExceptionHandler(SimpleMeterRegistry()))
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .build()

    @Test
    fun `shadow batch rejects more than one hundred sources`() {
        mvc.post("/api/v1/admin/application-identities/shadow-batches") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"afterSourceId":0,"limit":101,"resolutionRuleVersion":"application-identity-v1"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `shadow batch exposes typed result without starting background work`() {
        every { shadow.process(0, 100, "application-identity-v1") } returns ApplicationIdentityShadowBatchResult(
            2, 1, 1, 0, 1, 2, 9, false, "application-identity-v1"
        )
        mvc.post("/api/v1/admin/application-identities/shadow-batches") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"afterSourceId":0,"limit":100,"resolutionRuleVersion":"application-identity-v1"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.processedCount") { value(2) }
            jsonPath("$.data.resolutionRuleVersion") { value("application-identity-v1") }
        }
    }

    @Test
    fun `shadow batch exposes a safe retryable reason when channel facts are incomplete`() {
        every { shadow.process(0, 100, "application-identity-v1") } throws
            ServiceException(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, "APPLICATION_SDK_MISSING")

        mvc.post("/api/v1/admin/application-identities/shadow-batches") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"afterSourceId":0,"limit":100,"resolutionRuleVersion":"application-identity-v1"}"""
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.retryable") { value(true) }
            jsonPath("$.details.reasonCode") { value("APPLICATION_SDK_MISSING") }
        }
    }

    @Test
    fun `rebind requires typed request id versions and reason`() {
        mvc.post("/api/v1/admin/application-identities/22222222-2222-4222-8222-222222222222/aliases/rebind") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"aliasId":"bad","expectedAliasVersion":0,"expectedTargetVersion":0,"requestId":"bad","reason":"短"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `rebind returns persisted CAS versions`() {
        every { admin.rebind(any()) } returns ApplicationAliasRebindResult(
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            2,
            "22222222-2222-4222-8222-222222222222",
            4,
            false
        )
        val path = "/api/v1/admin/application-identities/" +
            "22222222-2222-4222-8222-222222222222/aliases/rebind"
        mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = """{
                "aliasId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "expectedAliasVersion":1,
                "expectedTargetVersion":3,
                "requestId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "reason":"产品已确认属于同一应用"
            }""".trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.aliasVersion") { value(2) }
            jsonPath("$.data.targetMappingVersion") { value(4) }
        }
    }

    @Test
    fun `reviewed binding validates decision specific target fields`() {
        mvc.post("/api/v1/admin/application-identities/reviewed-bindings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
                "candidateDisplayName":"优学练习",
                "expectedSourceCount":60,
                "decision":"ALIAS_OF_EXISTING",
                "requestId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "reason":"产品负责人确认属于独立应用"
            }""".trimIndent()
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `reviewed binding returns governed identity and source count`() {
        every { admin.createReviewedBinding(any()) } returns ApplicationReviewedBindingResult(
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            ApplicationReviewedBindingDecision.NEW_APPLICATION,
            "22222222-2222-4222-8222-222222222222",
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            1L,
            1L,
            60L,
            false
        )
        mvc.post("/api/v1/admin/application-identities/reviewed-bindings") {
            contentType = MediaType.APPLICATION_JSON
            content = """{
                "candidateDisplayName":"优学练习",
                "expectedSourceCount":60,
                "decision":"NEW_APPLICATION",
                "requestId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "reason":"产品负责人确认属于独立应用"
            }""".trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.decision") { value("NEW_APPLICATION") }
            jsonPath("$.data.affectedSourceCount") { value(60) }
        }
    }
}
