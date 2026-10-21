package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.service.impl.station.StationLegacyExportImportServiceImpl
import com.feedback.audit.BusinessAuditService
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDateTime

class StationLegacyExportImportServiceTest {
    private val files = mockk<StationLegacyExportFileReader>()
    private val batches = mockk<StationLegacyImportApplicationService>()
    private val audits = mockk<BusinessAuditService>()
    private val service = StationLegacyExportImportServiceImpl(files, batches, audits)
    private val path = Path.of("legacy.csv")

    @BeforeEach
    fun setUp() {
        clearMocks(files, batches, audits)
        every { audits.enqueue(any()) } just runs
    }

    @Test
    fun `import commits only bounded batches and aggregates invalid inserted contact`() {
        val rows = (1..201).map(::row)
        every { files.readImport(path) } returns StationLegacyExportFile(rows, "a".repeat(64))
        every { batches.importBatch(match { it.size == 200 }, any(), any(), 1) } returns
            StationLegacyBatchResult(inserted = 200, invalidContacts = 1)
        every { batches.importBatch(match { it.size == 1 }, any(), any(), 2) } returns
            StationLegacyBatchResult(inserted = 1)

        val result = service.importControlledExport(path)

        assertEquals(201, result.inserted)
        assertEquals(1, result.invalidContacts)
        assertEquals(2, result.successfulBatches)
        verify(exactly = 2) { batches.importBatch(any(), any(), any(), any()) }
    }

    @Test
    fun `failure audit cannot hide first batch error`() {
        val original = IllegalStateException("STATION_LEGACY_CHANNEL_STATE_CHANGED")
        val auditFailure = IllegalStateException("AUDIT_UNAVAILABLE")
        every { files.readImport(path) } returns StationLegacyExportFile(listOf(row(1)), "b".repeat(64))
        every { batches.importBatch(any(), any(), any(), any()) } throws original
        every { audits.enqueue(any()) } throws auditFailure

        val thrown = assertThrows(IllegalStateException::class.java) { service.importControlledExport(path) }

        assertSame(original, thrown)
        assertSame(auditFailure, thrown.suppressed.single())
    }

    private fun row(index: Int) = StationLegacyExportRow(
        index + 1, "legacy-$index", "p", "title", "content",
        LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 1, 1, 0, 0),
        null, null, null, null, null, null, null, null, null, null, emptyList(), "OPEN"
    )
}
