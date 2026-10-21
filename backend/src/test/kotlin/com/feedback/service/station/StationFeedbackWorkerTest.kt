package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.RemoteException
import com.feedback.common.exception.ServiceException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.station.online.StationOnlineChannelPo
import com.feedback.repository.station.online.StationOnlineChannelRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

class StationFeedbackWorkerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `expired source evidence is terminal but other attachment failures retry`() {
        assertTrue(isTerminalStationEvidenceUnavailable(remote("STATION_ATTACHMENT_HTTP_404")))
        assertTrue(isTerminalStationEvidenceUnavailable(remote("STATION_ATTACHMENT_HTTP_410")))
        assertTrue(isTerminalStationEvidenceUnavailable(remote("STATION_ATTACHMENT_MIME_MISMATCH")))
        assertTrue(isTerminalStationEvidenceUnavailable(client("STATION_ATTACHMENT_PRIVATE_ADDRESS")))
        assertFalse(isTerminalStationEvidenceUnavailable(remote("STATION_ATTACHMENT_HTTP_429")))
        assertFalse(isTerminalStationEvidenceUnavailable(remote("STATION_ATTACHMENT_HTTP_503")))
        assertFalse(isTerminalStationEvidenceUnavailable(remote("STATION_ATTACHMENT_IO_FAILED")))
    }

    @Test
    fun `invalid source contact is omitted without blocking feedback evidence`() {
        assertTrue(isTerminalStationContactUnavailable(client("STATION_CONTACT_VALUE_INVALID")))
        assertTrue(isTerminalStationContactUnavailable(client("STATION_PHONE_FORMAT_INVALID")))
        assertTrue(isTerminalStationContactUnavailable(client("STATION_PHONE_BASE64_INVALID")))
        assertTrue(isTerminalStationContactUnavailable(client("STATION_PHONE_DECRYPT_FAILED")))
        assertFalse(isTerminalStationContactUnavailable(client("STATION_ATTACHMENT_HTTP_503")))
    }

    @Test
    fun `expired processing lease is reclaimed after application restart`() {
        val channels = mockk<StationOnlineChannelRepository>()
        every { channels.claimOne("worker-test") } returns StationOnlineChannelPo(
            sourceId = 71L, leaseOwner = "worker-test", ingestStatus = "PROCESSING",
            authenticatedProductCode = "p"
        )
        val worker = StationFeedbackWorker(
            channels, mockk<StationPhoneResolver>(), jacksonObjectMapper(),
            mockk<StationAttachmentDownloader>(), mockk<StationOnlineFinalizationService>(), "worker-test"
        )

        assertEquals(71L, worker.claimOne())
        verify(exactly = 1) { channels.claimOne("worker-test") }
        worker.shutdownLeaseHeartbeat()
    }

    @Test
    fun `dirty marker failure is not converted to ordinary retry state`() {
        val channels = mockk<StationOnlineChannelRepository>()
        val finalization = mockk<StationOnlineFinalizationService>()
        every { channels.get(71) } returns StationOnlineChannelPo(
            sourceId = 71, leaseOwner = "worker-test", ingestStatus = "PROCESSING",
            contactStatus = "ENCRYPTED", authenticatedProductCode = "p"
        )
        every { finalization.finalizeSuccess(71, "worker-test", null, false, emptyList(), 0) } throws
            ServiceException(ErrorCode.STATION_DIRTY_MARKING_FAILED, "STATION_DIRTY_MARK_FAILED", cause = IllegalStateException("DIRTY_DOWN"))
        val worker = StationFeedbackWorker(
            channels, mockk(), jacksonObjectMapper(), mockk(), finalization, "worker-test"
        )

        val error = assertThrows(ServiceException::class.java) { worker.processOne(71) }
        assertEquals(ErrorCode.STATION_DIRTY_MARKING_FAILED, error.errorCode)
        verify(exactly = 0) { finalization.finalizeFailure(any(), any(), any(), any(), any(), any(), any()) }
        worker.shutdownLeaseHeartbeat()
    }

    @Test
    fun `prepared attachment is discarded when final lease renewal fails`() {
        val channels = mockk<StationOnlineChannelRepository>()
        val downloader = mockk<StationAttachmentDownloader>()
        val finalization = mockk<StationOnlineFinalizationService>()
        val attachmentPath = tempDir.resolve("prepared.jpg")
        Files.write(attachmentPath, byteArrayOf(0x01))
        val preparedAttachment = PreparedStationAttachment(
            sourceId = 71,
            sourceAttachmentId = "station:71:0:test",
            mimeType = "image/jpeg",
            fileSizeBytes = 1,
            fileSha256 = ByteArray(32) { 1 },
            storagePath = attachmentPath,
            retentionUntil = LocalDateTime.now().plusDays(30),
            ownsFile = true
        )
        every { channels.get(71) } returns StationOnlineChannelPo(
            sourceId = 71,
            leaseOwner = "worker-test",
            ingestStatus = "PROCESSING",
            contactStatus = "ENCRYPTED",
            authenticatedProductCode = "p",
            pendingPhotoUrls = "[\"https://example.com/evidence.jpg\"]"
        )
        var renewals = 0
        every { channels.renewLease(71, "worker-test") } answers {
            renewals++
            if (renewals == 2) throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_CHANNEL_LEASE_LOST")
        }
        every { downloader.validateUrl("https://example.com/evidence.jpg") } returns
            URI.create("https://example.com/evidence.jpg")
        every { downloader.sourceIdentity(71, 0, any()) } returns preparedAttachment.sourceAttachmentId
        every { downloader.prepare(71, "https://example.com/evidence.jpg", 0) } returns preparedAttachment
        every { finalization.existingAttachmentIdentities(listOf(preparedAttachment.sourceAttachmentId)) } returns emptySet()
        every { finalization.finalizeFailure(71, "worker-test", "RETRY_PENDING", 1, 5, false, "STATION_CHANNEL_LEASE_LOST") } returns Unit
        val worker = StationFeedbackWorker(
            channels, mockk(), jacksonObjectMapper(), downloader, finalization, "worker-test"
        )

        worker.processOne(71)

        assertEquals(2, renewals)
        assertFalse(Files.exists(attachmentPath))
        verify(exactly = 0) { finalization.finalizeSuccess(any(), any(), any(), any(), any(), any()) }
        worker.shutdownLeaseHeartbeat()
    }

    private fun client(reasonCode: String) = ClientException(ErrorCode.BAD_REQUEST, reasonCode)
    private fun remote(reasonCode: String) = RemoteException(ErrorCode.STATION_ATTACHMENT_UNAVAILABLE, reasonCode)
}
