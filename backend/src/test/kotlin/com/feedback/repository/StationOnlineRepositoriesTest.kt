package com.feedback.repository

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.station.online.StationDualWriteTaskMapper
import com.feedback.model.po.station.online.StationDualWriteTaskPo
import com.feedback.mapper.station.online.StationOnlineChannelMapper
import com.feedback.mapper.station.online.StationOnlineSourceMapper
import com.feedback.model.po.station.online.StationOnlineSourcePo
import com.feedback.mapper.station.online.StationOnlineAttachmentMapper
import com.feedback.model.po.station.online.StationOnlineAttachmentPo
import com.feedback.repository.impl.station.online.MyBatisStationDualWriteTaskRepositoryImpl
import com.feedback.repository.impl.station.online.MyBatisStationOnlineAttachmentRepositoryImpl
import com.feedback.repository.impl.station.online.MyBatisStationOnlineChannelRepositoryImpl
import com.feedback.repository.impl.station.online.MyBatisStationOnlineSourceRepositoryImpl
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StationOnlineRepositoriesTest {
    @Test
    fun `source upsert explicitly accepts MySQL zero one and two affected rows`() {
        listOf(0, 1, 2).forEach { affected ->
            val mapper = mockk<StationOnlineSourceMapper>()
            every { mapper.insertOrLocate(any()) } answers { firstArg<StationOnlineSourcePo>().id = 9; affected }
            assertDoesNotThrow { MyBatisStationOnlineSourceRepositoryImpl(mapper).insertOrLocate(StationOnlineSourcePo()) }
        }
        val mapper = mockk<StationOnlineSourceMapper>()
        every { mapper.insertOrLocate(any()) } answers { firstArg<StationOnlineSourcePo>().id = 9; 3 }
        val error = assertThrows(ServiceException::class.java) {
            MyBatisStationOnlineSourceRepositoryImpl(mapper).insertOrLocate(StationOnlineSourcePo())
        }
        assertEquals(ErrorCode.INTERNAL_ERROR, error.errorCode)
        assertEquals("STATION_SOURCE_UPSERT_FAILED", error.reasonCode)
    }

    @Test
    fun `dual write upsert re-reads authoritative row for concurrent unique key resolution`() {
        val mapper = mockk<StationDualWriteTaskMapper>()
        val input = StationDualWriteTaskPo(idempotencyKey = "station:12345678")
        val persisted = input.copy(id = 17, receiptId = ByteArray(16), requestFingerprint = ByteArray(32))
        every { mapper.insertOrLocate(input) } returns 2
        every { mapper.findByIdempotencyKey(input.idempotencyKey) } returns persisted

        MyBatisStationDualWriteTaskRepositoryImpl(mapper).insertOrLocate(input)

        verify(exactly = 1) { mapper.findByIdempotencyKey("station:12345678") }
    }

    @Test
    fun `retry batch is rejected before mapper when it exceeds one hundred sources`() {
        val mapper = mockk<StationOnlineChannelMapper>()
        val repository = MyBatisStationOnlineChannelRepositoryImpl(mapper)
        val error = assertThrows(ClientException::class.java) {
            repository.resetRetryable((1L..101L).toList())
        }
        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("STATION_SOURCE_IDS_INVALID", error.reasonCode)
        verify(exactly = 0) { mapper.resetRetryable(any()) }
    }

    @Test
    fun `worker state CAS failure is fail closed`() {
        val mapper = mockk<StationOnlineChannelMapper>()
        every { mapper.complete(7, "worker", 0, false) } returns 0
        val error = assertThrows(ServiceException::class.java) {
            MyBatisStationOnlineChannelRepositoryImpl(mapper).complete(7, "worker", 0, false)
        }
        assertEquals(ErrorCode.INTERNAL_ERROR, error.errorCode)
        assertEquals("STATION_CHANNEL_COMPLETE_FAILED", error.reasonCode)
    }

    @Test
    fun `attachment batch is rejected before mapper when it exceeds nine`() {
        val mapper = mockk<StationOnlineAttachmentMapper>()
        val rows = (0..9).map { index -> StationOnlineAttachmentPo(sourceAttachmentId = "station:7:$index:${"a".repeat(64)}") }
        val error = assertThrows(ClientException::class.java) {
            MyBatisStationOnlineAttachmentRepositoryImpl(mapper).insertOrLocateBatch(rows)
        }
        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("STATION_ATTACHMENT_BATCH_INVALID", error.reasonCode)
        verify(exactly = 0) { mapper.insertOrLocateBatch(any()) }
    }
}
