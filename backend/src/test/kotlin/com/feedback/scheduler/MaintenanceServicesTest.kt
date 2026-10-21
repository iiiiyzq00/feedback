package com.feedback.scheduler

import com.feedback.model.po.scheduler.MaintenanceAttachmentCandidatePo
import com.feedback.model.po.scheduler.MaintenanceSdkCipherCandidatePo
import com.feedback.model.po.scheduler.MaintenanceStationCipherCandidatePo
import com.feedback.repository.scheduler.MaintenanceLeaseRepository
import com.feedback.repository.scheduler.SensitiveRetentionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MaintenanceServicesTest {
    private val audit = mockk<MaintenanceAuditRecorder>(relaxed = true)

    @Test
    fun `expired leases preserve repository state machine results`() {
        val repository = mockk<MaintenanceLeaseRepository>()
        every { repository.recoverImports() } returns 2
        every { repository.recoverAiTasks() } returns 3

        val result = StaleLeaseRecovery(repository, audit).recover()

        assertEquals(mapOf("importBatches" to 2, "aiTasks" to 3), result)
        verify { audit.record("STALE_LEASE_RECOVERY", "WORKERS", result) }
    }

    @Test
    fun `cipher cleanup filters protected sources and securely deletes attachment`(@TempDir root: Path) {
        val file = Files.writeString(root.resolve("expired.enc"), "ciphertext")
        val repository = mockk<SensitiveRetentionRepository>()
        every { repository.sdkCandidates(null, 500) } returns listOf(
            MaintenanceSdkCipherCandidatePo(1, 10),
            MaintenanceSdkCipherCandidatePo(2, 11),
        )
        every { repository.activeAlertSourceIds(listOf(10L, 11L)) } returns setOf(10)
        every { repository.clearSdk(listOf(2L)) } returns 1
        every { repository.clearImportRows() } returns 2
        every { repository.stationCandidates(null, 500) } returns listOf(MaintenanceStationCipherCandidatePo(3, 20))
        every { repository.maskingStatuses(listOf(20L)) } returns mapOf(20L to "PASS")
        every { repository.clearStation(listOf(3L)) } returns 1
        every { repository.clearAiSnapshots() } returns 3
        every { repository.clearExpiredContacts() } returns 4
        every { repository.clearFinishedOrExpiredUrls() } returns 5
        every { repository.expireUploadSessions() } returns (6 to 7)
        every { repository.attachmentCandidates(500) } returns listOf(MaintenanceAttachmentCandidatePo(9, file.toString()))
        every { repository.markAttachmentsDeleted(listOf(9L)) } returns 1

        val result = CipherCleanupTask(repository, audit, root.toString()).cleanup()

        assertFalse(Files.exists(file))
        assertEquals(1, result["sdkEnvelopes"])
        assertEquals(1, result["stationPayloads"])
        assertEquals(1, result["attachments"])
        assertEquals(3, result["aiSnapshots"])
        verify(exactly = 0) { repository.clearSdk(listOf(1L)) }
        verify { audit.record("CIPHER_RETENTION_CLEANUP", "SENSITIVE_DATA", result) }
    }

    @Test
    fun `cipher cleanup rejects attachment paths outside configured root`(@TempDir root: Path) {
        val repository = mockk<SensitiveRetentionRepository>(relaxed = true)
        every { repository.sdkCandidates(null, 500) } returns emptyList()
        every { repository.stationCandidates(null, 500) } returns emptyList()
        every { repository.expireUploadSessions() } returns (0 to 0)
        every { repository.attachmentCandidates(500) } returns listOf(
            MaintenanceAttachmentCandidatePo(9, root.parent.resolve("outside.enc").toString())
        )

        assertThrows(SecurityException::class.java) {
            CipherCleanupTask(repository, audit, root.toString()).cleanup()
        }
        verify(exactly = 0) { repository.markAttachmentsDeleted(any()) }
    }
}
