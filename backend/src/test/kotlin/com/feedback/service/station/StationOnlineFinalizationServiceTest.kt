package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.service.impl.station.StationOnlineFinalizationServiceImpl

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.station.online.StationOnlineAttachmentPo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.repository.station.online.StationOnlineAttachmentRepository
import com.feedback.repository.station.online.StationOnlineChannelRepository
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDateTime

class StationOnlineFinalizationServiceTest {
    private val channels = mockk<StationOnlineChannelRepository>()
    private val identities = mockk<StationOnlineIdentityService>()
    private val attachments = mockk<StationOnlineAttachmentRepository>()
    private val marker = mockk<FeedbackListProjectionDirtyMarker>()
    private val service = StationOnlineFinalizationServiceImpl(channels, identities, attachments, marker)

    @Test
    fun `formal facts complete before channel identity and attachment dirty reasons`() {
        val prepared = prepared()
        every { identities.storePhone(7, "13800138000", "UC_USER_LOOKUP") } returns true
        every { attachments.findBySourceIdentities(listOf(prepared.sourceAttachmentId)) } returns emptyList()
        every { attachments.findBySourceAndHashes(7, listOf(prepared.fileSha256)) } returns emptyList()
        every { attachments.insertOrLocateBatch(any()) } answers {
            firstArg<List<StationOnlineAttachmentPo>>().map { it.copy(id = 11) }
        }
        every { channels.complete(7, "worker", 0, true) } just runs
        every { marker.mark(any()) } just runs

        service.finalizeSuccess(
            7, "worker", ResolvedStationPhone("13800138000", "UC_USER_LOOKUP"), false,
            listOf(prepared), 0
        )

        verifyOrder {
            attachments.insertOrLocateBatch(any())
            channels.complete(7, "worker", 0, true)
            marker.mark(match { it.reason == FeedbackListProjectionDirtyReason.CHANNEL })
            marker.mark(match { it.reason == FeedbackListProjectionDirtyReason.IDENTITY })
            marker.mark(match { it.reason == FeedbackListProjectionDirtyReason.ATTACHMENT })
        }
        verify(exactly = 0) { attachments.findBySourceIdentity(any()) }
        verify(exactly = 0) { attachments.findBySourceAndHash(any(), any()) }
        verify(exactly = 0) { attachments.insertOrLocate(any()) }
    }

    @Test
    fun `marker failure propagates as dedicated fail closed error`() {
        every { channels.complete(7, "worker", 0, false) } just runs
        every { marker.mark(match { it.reason == FeedbackListProjectionDirtyReason.CHANNEL }) } throws
            IllegalStateException("DIRTY_DOWN")

        val error = assertThrows(ServiceException::class.java) {
            service.finalizeSuccess(7, "worker", null, false, emptyList(), 0)
        }
        assertEquals(ErrorCode.STATION_DIRTY_MARKING_FAILED, error.errorCode)
    }

    @Test
    fun `failure state and channel marker share the fail closed finalization boundary`() {
        every { channels.fail(7, "worker", "RETRY_PENDING", 1, 5, false, "STATION_TIMEOUT", any()) } just runs
        every { marker.mark(match { it.reason == FeedbackListProjectionDirtyReason.CHANNEL }) } throws
            IllegalStateException("DIRTY_DOWN")

        val error = assertThrows(ServiceException::class.java) {
            service.finalizeFailure(7, "worker", "RETRY_PENDING", 1, 5, false, "STATION_TIMEOUT")
        }
        assertEquals(ErrorCode.STATION_DIRTY_MARKING_FAILED, error.errorCode)
        verify(exactly = 1) { channels.fail(7, "worker", "RETRY_PENDING", 1, 5, false, "STATION_TIMEOUT", any()) }
    }

    private fun prepared() = PreparedStationAttachment(
        sourceId = 7,
        sourceAttachmentId = "station:7:0:${"a".repeat(64)}",
        mimeType = "image/jpeg",
        fileSizeBytes = 12,
        fileSha256 = ByteArray(32) { 1 },
        storagePath = Path.of("build/station-test.jpg"),
        retentionUntil = LocalDateTime.now().plusDays(30),
        ownsFile = false
    )
}
