package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.dto.station.StationAcceptResult
import com.feedback.rpc.api.FeedbackRpcRequest
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.feedback.config.StationDualWriteProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FeedbackPlatformRpcApiImplTest {
    private val ingest = mockk<StationFeedbackIngestService>()
    private val tasks = mockk<StationDualWriteTaskService>()
    private val properties = StationDualWriteProperties()
    private val meters = SimpleMeterRegistry()
    private val api = FeedbackPlatformRpcApiImpl(ingest, ObjectMapper(), tasks, properties, meters)

    @Test
    fun `valid rpc feedback enters station ingestion and returns legacy success status`() {
        val requestSlot = slot<com.feedback.dto.station.StationFeedbackRequest>()
        every { ingest.accept(capture(requestSlot), any(), any(), any()) } returns
            StationAcceptResult("receipt-1", 1L)

        val response = api.submit(validRequest())

        assertEquals("0", response.status)
        assertEquals("receipt-1", response.receiptId)
        assertEquals("p-1", requestSlot.captured.productId)
        assertEquals("13800138000", requestSlot.captured.phone)
        assertEquals(1.0, meters.get("feedback.station.rpc.requests").tag("outcome", "success").counter().count())
        verify(exactly = 1) { ingest.accept(any(), any(), "station:12345678", any()) }
    }

    @Test
    fun `invalid rpc feedback returns legacy failure status without ingestion`() {
        val response = api.submit(validRequest().apply { apiVersion = "V3" })

        assertEquals("1", response.status)
        assertEquals(1.0, meters.get("feedback.station.rpc.requests").tag("outcome", "failure").counter().count())
        verify(exactly = 0) { ingest.accept(any(), any(), any(), any()) }
    }

    @Test
    fun `enabled reliable dual write returns after durable task acceptance`() {
        properties.enabled = true
        every { tasks.accept(any()) } returns StationDualWriteAcceptResult("queued-receipt", 9L)

        val response = api.submit(validRequest())

        assertEquals("0", response.status)
        assertEquals("queued-receipt", response.receiptId)
        verify(exactly = 1) { tasks.accept(any()) }
        verify(exactly = 0) { ingest.accept(any(), any(), any(), any()) }
    }

    private fun validRequest() = FeedbackRpcRequest().apply {
        apiVersion = "V1"
        idempotencyKey = "station:12345678"
        productCode = "study"
        resolvedUserId = "user-1"
        productId = "p-1"
        appVersion = "1.2.3"
        title = "title"
        content = "content"
        phone = "13800138000"
        platform = "android"
        feedbackAttributeRecordsJson = "[]"
    }
}
