package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.service.impl.station.StationOnlineMaskingRecoveryServiceImpl
import com.feedback.manager.station.StationOnlineMaskingRecoveryTransaction
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.common.exception.ClientException
import com.feedback.model.po.station.online.StationOnlineChannelPo
import com.feedback.model.po.station.online.StationOnlineContentPo
import com.feedback.model.po.station.online.StationOnlineSourcePo
import com.feedback.repository.station.online.StationOnlineChannelRepository
import com.feedback.repository.station.online.StationOnlineContentRepository
import com.feedback.repository.station.online.StationOnlineSourceRepository
import com.feedback.service.masking.MaskingPipeline
import com.feedback.service.masking.MaskingResult
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StationOnlineMaskingRecoveryServiceTest {
    private val sources = mockk<StationOnlineSourceRepository>()
    private val contents = mockk<StationOnlineContentRepository>()
    private val channels = mockk<StationOnlineChannelRepository>()
    private val raw = mockk<StationRawPayloadService>()
    private val masking = mockk<MaskingPipeline>()
    private val transactions = mockk<StationOnlineMaskingRecoveryTransaction>()
    private val service = StationOnlineMaskingRecoveryServiceImpl(sources, contents, channels, raw, masking, transactions)
    private val json = jacksonObjectMapper()

    @Test
    fun `only REALTIME raw payloads are recovered while legacy and unknown modes remain untouched`() {
        val ids = listOf(1L, 2L, 3L)
        every { contents.findMaskingReviewSourceIdsAfterId(0, 3) } returns ids
        every { sources.findByIds(ids) } returns ids.map { StationOnlineSourcePo(id = it) }
        every { contents.findBySourceIds(ids) } returns ids.map { StationOnlineContentPo(sourceId = it, maskingStatus = "REVIEW_NEEDED") }
        every { channels.findBySourceIds(ids) } returns ids.map { StationOnlineChannelPo(sourceId = it, authenticatedProductCode = "p") }
        every { raw.inspectManySafely(ids) } returns mapOf(
            1L to json.readTree("""{"sourceMode":"REALTIME","title":"t","content":"c"}"""),
            2L to json.readTree("""{"sourceMode":"LEGACY","title":"t","content":"c"}"""),
            3L to json.readTree("""{"title":"t","content":"c"}""")
        )
        every { masking.mask(any(), any(), any()) } answers { MaskingResult(firstArg(), "PASS", emptyList()) }
        every { transactions.markRecovered(any()) } just runs

        assertEquals(1, service.recover(3))
        verify(exactly = 1) { transactions.markRecovered(match { it.sourceId == 1L }) }
        verify(exactly = 0) { transactions.markRecovered(match { it.sourceId == 2L || it.sourceId == 3L }) }
    }

    @Test
    fun `recovery rejects over two hundred before mapper access`() {
        assertThrows(ClientException::class.java) { service.recover(201) }
        verify(exactly = 0) { contents.findMaskingReviewSourceIdsAfterId(any(), any()) }
    }

    @Test
    fun `recovery transaction wraps marker failure for fail closed propagation`() {
        val marker = mockk<com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker>()
        val transaction = StationOnlineMaskingRecoveryTransaction(contents, channels, marker)
        every { contents.markMaskingPass(any()) } just runs
        every { channels.markMaskingRecovered(7) } just runs
        every { marker.mark(any()) } throws IllegalStateException("DIRTY_DOWN")

        val error = assertThrows(ServiceException::class.java) {
            transaction.markRecovered(StationOnlineContentPo(sourceId = 7, maskingStatus = "REVIEW_NEEDED"))
        }
        assertEquals(ErrorCode.STATION_DIRTY_MARKING_FAILED, error.errorCode)
    }
}
