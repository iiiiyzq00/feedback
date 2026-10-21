package com.feedback.service.sdk

import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.strategy.sdk.*
import com.feedback.security.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*

import com.feedback.manager.sdk.SdkTelemetryCommitWorker
import com.feedback.service.impl.sdk.SdkTelemetryServiceImpl
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.dto.sdk.SdkTelemetryRequest
import com.feedback.dto.sdk.TelemetryEventDto
import com.feedback.model.po.sdk.SdkTransportTelemetryPo
import com.feedback.repository.sdk.SdkTelemetryRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SdkTelemetryPersistenceTest {
    private val telemetryRepository = mockk<SdkTelemetryRepository>()
    private val service = SdkTelemetryServiceImpl(
        telemetryRepository,
        jacksonObjectMapper(),
        SdkTelemetryCommitWorker(telemetryRepository)
    )

    @Test
    fun `persists telemetry in one batch without raw payload`() {
        val rows = slot<List<SdkTransportTelemetryPo>>()
        every { telemetryRepository.insertTransportEvents(capture(rows)) } returns Unit

        service.ingestTelemetry(
            SdkTelemetryRequest(events = listOf(TelemetryEventDto(
                clientFeedbackId = "client-1",
                failureStage = "ATTACHMENT_UPLOAD",
                errorType = "HTTP_500",
                networkType = "WIFI",
                retryCount = 2,
                attachmentSizeBytes = 4096,
                sdkVersion = "1.0.0",
                appVersion = "1.1.0",
                durationMs = 1234
            ))),
            appKey = "course",
            clientIp = "127.0.0.1"
        )

        verify(exactly = 1) { telemetryRepository.insertTransportEvents(any()) }
        assertEquals(1, rows.captured.size)
        val row = rows.captured.single()
        assertEquals("client-1", row.targetId)
        assertEquals("127.0.0.1", row.ipAddress)
        val detail = jacksonObjectMapper().readTree(row.detailJson)
        assertEquals("course", detail["app_key"].asText())
        assertEquals(null, detail["raw_payload"])
    }
}
