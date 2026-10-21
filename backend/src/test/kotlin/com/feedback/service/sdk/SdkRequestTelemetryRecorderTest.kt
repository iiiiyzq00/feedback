package com.feedback.service.sdk

import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.strategy.sdk.*
import com.feedback.security.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import com.feedback.repository.sdk.SdkTelemetryRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class SdkRequestTelemetryRecorderTest {
    private val recorder=SdkRequestTelemetryRecorder(mockk<SdkTelemetryRepository>(), jacksonObjectMapper())

    @Test
    fun `classifies every transport result type`() {
        mapOf(200 to "SUCCESS",299 to "SUCCESS",429 to "RATE_LIMITED",401 to "AUTH_FAILED",403 to "AUTH_FAILED",
            400 to "BAD_REQUEST",422 to "BAD_REQUEST",500 to "SERVER_ERROR",599 to "SERVER_ERROR",404 to "OTHER_ERROR",302 to "OTHER_ERROR")
            .forEach { (status, expected) -> assertEquals(expected,recorder.classify(status),"HTTP $status") }
    }
}
