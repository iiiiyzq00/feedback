package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.service.impl.station.StationFeedbackOperationsServiceImpl
import com.feedback.service.impl.station.StationOnlineRetryServiceImpl

import com.feedback.common.exception.ClientException
import com.feedback.audit.BusinessAuditService
import com.feedback.model.po.station.online.StationOnlineChannelPo
import com.feedback.model.po.station.online.StationOperationsTaskPo
import com.feedback.repository.station.online.StationOnlineChannelRepository
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import com.feedback.security.WebPrincipal
import java.util.Base64

class StationFeedbackOperationsServiceTest {
    private val operationsChannels = mockk<StationOnlineChannelRepository>()
    private val audits = mockk<BusinessAuditService>(relaxed = true)
    private val service = StationFeedbackOperationsServiceImpl(operationsChannels)

    @BeforeEach
    fun authenticate() {
        val principal = WebPrincipal(99, "operator", "", "运营", setOf("OPERATOR"), emptySet())
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities)
    }

    @AfterEach
    fun clearAuthentication() = SecurityContextHolder.clearContext()

    @Test
    fun `lists only station channel task metadata`() {
        val receipt = ByteArray(16) { it.toByte() }
        every { operationsChannels.operations("RETRY_PENDING",1,20) } returns (listOf(StationOperationsTaskPo(
            sourceId=7,receiptId=receipt,apiVersion="V1",productId="p",appId=null,platform="ANDROID",subChannel="APP",
            status="RETRY_PENDING",processingStage="CONTACT",contactStatus="NOT_PROCESSED",attributeStatus="MAPPED",
            retryCount=1,maxRetryCount=5,nextRetryAt=null,errorCode=null,errorSummary=null,
            receivedAt=java.time.LocalDateTime.now(),processedAt=null,retryable=true
        )) to 1L)
        every { operationsChannels.operationsSummary() } returns mapOf("RETRY_PENDING" to 1L)

        val result = service.tasks("retry_pending", 1, 20)
        val record = (result["records"] as List<*>).first() as Map<*, *>

        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(receipt), record["receiptId"])
        assertEquals("STATION_FEEDBACK", result["channel"])
        assertEquals("旧反馈平台兼容渠道；不属于通用反馈SDK渠道", result["channelBoundary"])
    }

    @Test
    fun `manual retry only resets retryable station states`() {
        val receipt = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { it.toByte() })
        val channels = mockk<StationOnlineChannelRepository>()
        val marker = mockk<FeedbackListProjectionDirtyMarker>()
        val retry = StationOnlineRetryServiceImpl(channels, marker, audits)
        every { channels.findRetryableByReceiptsForUpdate(any()) } returns listOf(
            StationOnlineChannelPo(sourceId = 7, receiptId = ByteArray(16), authenticatedProductCode = "p")
        )
        every { channels.resetRetryable(listOf(7L)) } just runs
        every { marker.mark(any()) } just runs

        val result = retry.retry(StationRetryRequest(listOf(receipt)))

        assertEquals(1, result.queued)
        assertEquals(0, result.skipped)
        verify { channels.resetRetryable(listOf(7L)) }
        verify { marker.mark(match { it.sourceIds == listOf(7L) }) }
        verify { audits.enqueue(match { it.operation == "STATION_INGEST_RETRY" && it.targetType == "STATION_INGEST" }) }
    }

    @Test
    fun `rejects invalid receipt without touching either channel`() {
        val channels = mockk<StationOnlineChannelRepository>()
        val retry = StationOnlineRetryServiceImpl(channels, mockk(), audits)
        assertThrows(ClientException::class.java) {
            retry.retry(StationRetryRequest(listOf("not-a-receipt")))
        }
        verify(exactly = 0) { channels.findRetryableByReceiptsForUpdate(any()) }
    }

    @Test
    fun `manual retry rejects missing authenticated identity before mapper access`() {
        SecurityContextHolder.clearContext()
        val channels = mockk<StationOnlineChannelRepository>()
        val retry = StationOnlineRetryServiceImpl(channels, mockk(), audits)
        val receipt = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))

        assertThrows(ClientException::class.java) {
            retry.retry(StationRetryRequest(listOf(receipt)))
        }
        verify(exactly = 0) { channels.findRetryableByReceiptsForUpdate(any()) }
    }

    @Test
    fun `manual retry marker failure propagates and audit is not accepted`() {
        val channels = mockk<StationOnlineChannelRepository>()
        val marker = mockk<FeedbackListProjectionDirtyMarker>()
        val retry = StationOnlineRetryServiceImpl(channels, marker, audits)
        val receipt = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        every { channels.findRetryableByReceiptsForUpdate(any()) } returns listOf(
            StationOnlineChannelPo(sourceId = 7, authenticatedProductCode = "p")
        )
        every { channels.resetRetryable(listOf(7L)) } just runs
        every { marker.mark(any()) } throws IllegalStateException("DIRTY_DOWN")

        assertThrows(IllegalStateException::class.java) {
            retry.retry(StationRetryRequest(listOf(receipt)))
        }
        verify(exactly = 0) { audits.enqueue(any()) }
    }
}
