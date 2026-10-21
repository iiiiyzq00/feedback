package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.service.impl.station.StationFeedbackIngestServiceImpl

import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.dto.station.StationApiVersion
import com.feedback.dto.station.StationAcceptResult
import com.feedback.dto.station.StationFeedbackRequest
import com.feedback.security.station.StationGatewayPrincipal
import com.feedback.service.masking.MaskingPipeline
import com.feedback.service.masking.MaskingResult
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StationFeedbackIngestServiceTest {
    private val principal = StationGatewayPrincipal("gateway", "auth-user", "legacy-product")
    private val masking = mockk<MaskingPipeline>()
    private val identities = mockk<StationOnlineIdentityService>()
    private val rawPayloads = mockk<StationRawPayloadService>()
    private val commitWorker = mockk<StationFeedbackIngestCommitWorker>()
    private val mapper = jacksonObjectMapper()
    private val service = StationFeedbackIngestServiceImpl(
        mapper, masking, identities, rawPayloads, commitWorker
    )

    @BeforeEach
    fun setUp() {
        clearMocks(masking, identities, rawPayloads, commitWorker)
        every { masking.mask(any(), any(), any()) } answers { MaskingResult(firstArg(), "PASS", emptyList()) }
        every { rawPayloads.prepare(any(), any()) } returns PreparedStationRawPayload(
            byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), "station-raw-v1", java.time.LocalDateTime.now()
        )
        every { commitWorker.commit(any()) } returns StationAcceptResult("receipt", 41)
    }

    @Test
    fun `accept maps online facts and marks channel dirty`() {
        val request = request().copy(
            feedbackAttributeRecords = mapper.readTree("""[{"code":"scene","value":"classroom"}]"""),
            photoUrlList = listOf("https://cdn.example/a.jpg")
        )
        val result = service.accept(
            request, StationApiVersion.V2, "feedback-0001",
            StationGatewayPrincipal("gateway-client", "gateway-user", "legacy-product")
        )

        assertEquals(41, result.sourceId)
        verify { rawPayloads.prepare(match<StationOnlineRawPayload> { it.sourceMode == "REALTIME" && it.content == "无法打开课程" }, any()) }
        verify { commitWorker.commit(match {
            it.sourceRecordId == "feedback-0001" && it.safeContent == "无法打开课程" &&
                it.sourceApiVersion == "V2" && it.productId == "product-1" &&
                it.authenticatedUserId == "gateway-user" && it.pendingPhotoUrls!!.contains("cdn.example") &&
                it.contactStatus == "NOT_PROCESSED"
        }) }
    }

    @Test
    fun `V1 and V2 preserve deferred online contact semantics`() {
        service.accept(request().copy(phone = "+86 138-0013-8000"), StationApiVersion.V1, "feedback-v1", principal)
        verify(exactly = 0) { identities.preparePhone(any(), any()) }
        verify { commitWorker.commit(match { it.sourceApiVersion == "V1" && it.sourcePhoneCiphertext == null && it.contactStatus == "NOT_PROCESSED" }) }

        service.accept(request(), StationApiVersion.V2, "feedback-v2", principal)
        verify { commitWorker.commit(match { it.sourceApiVersion == "V2" && it.sourcePhoneCiphertext == "encrypted-phone" && it.contactStatus == "NOT_PROCESSED" }) }
    }

    @Test
    fun `idempotent replay returns existing receipt without formal fact inserts`() {
        val original = request()
        every { commitWorker.commit(any()) } returns StationAcceptResult("stable-receipt", 9)

        val result = service.accept(original, StationApiVersion.V1, "feedback-0001", principal)

        assertEquals(9, result.sourceId)
        verify(exactly = 1) { commitWorker.commit(any()) }
    }

    @Test
    fun `same idempotency key with different payload is rejected`() {
        val original = request()
        every { commitWorker.commit(any()) } throws ServiceException(
            com.feedback.common.ErrorCode.STATION_IDEMPOTENCY_CONFLICT,
            "STATION_IDEMPOTENCY_CONFLICT"
        )

        val error = assertThrows(ServiceException::class.java) {
            service.accept(original.copy(content = "另一条反馈"), StationApiVersion.V1, "feedback-0001", principal)
        }
        assertEquals("STATION_IDEMPOTENCY_CONFLICT", error.reasonCode)
        verify(exactly = 1) { commitWorker.commit(any()) }
    }

    @Test
    fun `replay without header derives stable request fingerprint key`() {
        val captured = slot<PreparedStationIngest>()
        every { commitWorker.commit(capture(captured)) } returns StationAcceptResult("receipt", 41)

        service.accept(request(), StationApiVersion.V1, null, principal)

        assertTrue(captured.captured.sourceRecordId.startsWith("fp:"))
        assertEquals(46, captured.captured.sourceRecordId.length)
    }

    @Test
    fun `invalid explicit idempotency key is rejected before persistence`() {
        val error = assertThrows(ClientException::class.java) {
            service.accept(request(), StationApiVersion.V1, "bad key", principal)
        }
        assertEquals("STATION_IDEMPOTENCY_KEY_INVALID", error.reasonCode)
        verify(exactly = 0) { commitWorker.commit(any()) }
    }

    @Test
    fun `dynamic attributes remain compatible but do not affect fingerprint`() {
        val base = request()
        val unexpected = base.copy(feedbackAttributeRecords = mapper.readTree("""{"code":"scene","value":"private"}"""))
        assertArrayEquals(
            service.requestFingerprint(base, StationApiVersion.V1, null),
            service.requestFingerprint(unexpected, StationApiVersion.V1, null)
        )
    }

    @Test
    fun `dirty marker failure propagates instead of returning an accepted receipt`() {
        every { commitWorker.commit(any()) } throws IllegalStateException("DIRTY_DOWN")

        assertThrows(IllegalStateException::class.java) {
            service.accept(request(), StationApiVersion.V1, "feedback-dirty", principal)
        }
    }

    private fun request() = StationFeedbackRequest(
        productId = "product-1", appVersion = "1.2.3", title = "课程异常", content = "无法打开课程",
        phone = "encrypted-phone", platform = "ANDROID", type = "BUG", channel = "APP",
        userId = "source-user", description = "补充说明", appId = "app-1", appName = "学习应用"
    )
}
