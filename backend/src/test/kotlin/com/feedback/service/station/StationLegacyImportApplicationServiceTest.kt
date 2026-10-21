package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.service.impl.station.StationLegacyImportApplicationServiceImpl
import com.feedback.audit.BusinessAuditService
import com.feedback.model.po.station.legacy.StationLegacyChannelPo
import com.feedback.model.po.station.legacy.StationLegacyChannelStatePo
import com.feedback.model.po.station.legacy.StationLegacyContentPo
import com.feedback.model.po.station.legacy.StationLegacyIdentityPo
import com.feedback.model.po.station.legacy.StationLegacySourcePo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.repository.station.legacy.StationLegacyRepository
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class StationLegacyImportApplicationServiceTest {
    private val repository = mockk<StationLegacyRepository>()
    private val assembler = mockk<StationLegacyRowAssembler>()
    private val rawPayloads = mockk<StationRawPayloadService>()
    private val marker = mockk<FeedbackListProjectionDirtyMarker>()
    private val audits = mockk<BusinessAuditService>()
    private val service = StationLegacyImportApplicationServiceImpl(
        repository, assembler, rawPayloads, jacksonObjectMapper(), marker, audits
    )

    @BeforeEach
    fun setUp() {
        clearMocks(repository, assembler, rawPayloads, marker, audits)
        every { marker.mark(any()) } just runs
        every { audits.enqueue(any()) } just runs
    }

    @Test
    fun `active worker state accepts first-write identity without rewriting channel workflow`() {
        val row = row(sourceUserId = "legacy-user")
        existingBatch(row, channel(ingestStatus = "RECEIVED", sourceUserId = "legacy-user"))
        every { assembler.userIdentity(11, "legacy-user") } returns StationLegacyIdentityPo(sourceId = 11, identityType = "USER_ID")
        every { repository.insertIdentity(any()) } returns true

        val result = service.importBatch(listOf(row), "run", "a".repeat(64), 1)

        assertEquals(1, result.enriched)
        verify(exactly = 0) { repository.applyChannelState(any()) }
        verify(exactly = 1) {
            marker.mark(match { it.reason == FeedbackListProjectionDirtyReason.IDENTITY && it.sourceIds == listOf(11L) })
        }
        verify(exactly = 0) { marker.mark(match { it.reason == FeedbackListProjectionDirtyReason.CHANNEL }) }
    }

    @Test
    fun `failed masking state is not bypassed by attachment or invalid contact enrichment`() {
        val row = row(phone = "bad", photoUrls = listOf("https://example.test/a.jpg"))
        existingBatch(row, channel(ingestStatus = "FAILED_FINAL"), maskingStatus = "FAILED")

        val result = service.importBatch(listOf(row), "run", "b".repeat(64), 1)

        assertEquals(1, result.invalidContacts)
        assertEquals(0, result.recovered)
        assertEquals(0, result.evidenceQueued)
        verify(exactly = 0) { repository.applyChannelState(any()) }
        verify(exactly = 0) { marker.mark(any()) }
    }

    @Test
    fun `recovery closes safe failed row even without new contact or attachments`() {
        val candidate = channel(ingestStatus = "FAILED_FINAL")
        every { repository.recoveryPageForUpdate(0) } returns listOf(candidate)
        every { repository.sourcesByIds(listOf(11)) } returns mapOf(11L to source())
        every { repository.contents(listOf(11)) } returns mapOf(11L to StationLegacyContentPo(sourceId = 11, maskingStatus = "PASS"))
        every { repository.identities(listOf(11)) } returns emptyMap()
        every { repository.sourcesWithEvidence(listOf(11)) } returns emptySet()
        every { assembler.phone(null) } returns StationLegacyPhoneDecision.Missing
        every { repository.applyChannelState(any()) } just runs

        val result = service.recoverPage(
            0, mapOf("legacy-1" to StationLegacyRecoveryRow(2, "legacy-1", null, emptyList())),
            "run", "c".repeat(64), 1
        )

        assertEquals(1, result.recovered)
        verify {
            repository.applyChannelState(match {
                it.sourceId == 11L && it.ingestStatus == "COMPLETED" && it.processingStage == "COMPLETED"
            })
        }
    }

    @Test
    fun `existing phone identity repairs completed contact status without rewriting identity`() {
        val row = row()
        existingBatch(row, channel(ingestStatus = "COMPLETED", contactStatus = "NOT_PROVIDED"), identities = setOf("PHONE"))
        val state = slot<StationLegacyChannelStatePo>()
        every { repository.applyChannelState(capture(state)) } just runs

        val result = service.importBatch(listOf(row), "run", "d".repeat(64), 1)

        assertEquals(1, result.enriched)
        assertEquals("ENCRYPTED", state.captured.contactStatus)
        verify(exactly = 0) { repository.insertIdentity(any()) }
    }

    @Test
    fun `dirty marker failure remains batch failure and prevents success audit`() {
        val row = row(sourceUserId = "legacy-user")
        existingBatch(row, channel(ingestStatus = "RECEIVED", sourceUserId = "legacy-user"))
        every { assembler.userIdentity(11, "legacy-user") } returns StationLegacyIdentityPo(sourceId = 11, identityType = "USER_ID")
        every { repository.insertIdentity(any()) } returns true
        every { marker.mark(any()) } throws IllegalStateException("MARKER_FAILED")

        val error = assertThrows(IllegalStateException::class.java) {
            service.importBatch(listOf(row), "run", "e".repeat(64), 1)
        }

        assertEquals("MARKER_FAILED", error.message)
        verify(exactly = 0) { audits.enqueue(any()) }
    }

    private fun existingBatch(
        row: StationLegacyExportRow,
        channel: StationLegacyChannelPo,
        maskingStatus: String = "PASS",
        identities: Set<String> = emptySet()
    ) {
        val source = source()
        every { assembler.prepare(row) } returns StationLegacyPreparedRow(
            row, row.title, row.content, row.sourceDescription,
            when (row.phone) {
                null -> StationLegacyPhoneDecision.Missing
                "bad" -> StationLegacyPhoneDecision.Invalid("STATION_PHONE_BASE64_INVALID")
                else -> StationLegacyPhoneDecision.Valid(row.phone)
            }
        )
        every { repository.sourcesByRecordIdsForUpdate(listOf(row.recordId)) } returns listOf(source)
        every { repository.contents(listOf(11)) } returns mapOf(11L to StationLegacyContentPo(sourceId = 11, maskingStatus = maskingStatus))
        every { repository.channelsForUpdate(listOf(11)) } returns mapOf(11L to channel)
        every { repository.rawSourceIds(listOf(11)) } returns setOf(11L)
        every { repository.identities(listOf(11)) } returns if (identities.isEmpty()) emptyMap() else mapOf(11L to identities)
        every { repository.sourcesWithEvidence(listOf(11)) } returns emptySet()
    }

    private fun source() = StationLegacySourcePo(id = 11, sourceRecordId = "legacy-1", appVersion = "1.0")

    private fun channel(
        ingestStatus: String,
        sourceUserId: String? = null,
        contactStatus: String = "NOT_PROVIDED"
    ) = StationLegacyChannelPo(
        sourceId = 11,
        productId = "UNKNOWN",
        sourceUserId = sourceUserId,
        contactStatus = contactStatus,
        ingestStatus = ingestStatus,
        processingStage = ingestStatus
    )

    private fun row(
        sourceUserId: String? = null,
        phone: String? = null,
        photoUrls: List<String> = emptyList()
    ) = StationLegacyExportRow(
        2, "legacy-1", "UNKNOWN", "title", "content",
        LocalDateTime.of(2024, 1, 2, 3, 4, 5), LocalDateTime.of(2024, 1, 2, 4, 5, 6),
        null, null, null, "1.0", null, null, null, null, sourceUserId, phone, photoUrls, "OPEN"
    )
}
