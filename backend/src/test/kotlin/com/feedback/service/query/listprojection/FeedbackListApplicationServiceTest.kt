package com.feedback.service.query.listprojection

import com.feedback.application.port.feedback.list.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.audit.BusinessAuditService
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.config.FeedbackListReadProperties
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionGenerationPo
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.model.auth.DataAccessScope
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.model.auth.DataPermissionSnapshot
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.AuthProvider
import com.feedback.service.masking.OutboundPrivacyGate
import com.feedback.service.query.CommonFeedbackFilters
import com.feedback.service.query.FeedbackListQuery
import com.feedback.service.query.FeedbackPageRequest
import com.feedback.service.query.FeedbackSortSpec
import com.feedback.service.query.RedblackFeedbackFilters
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FeedbackListApplicationServiceTest {
    private val repository = mockk<FeedbackListReadPort>()
    private val permissions = mockk<DataPermissionService>()
    private val principal = mockk<CurrentWebPrincipalProvider>()
    private val privacy = mockk<OutboundPrivacyGate>()
    private val audits = mockk<BusinessAuditService>()
    private val generationProvider = mockk<ActiveGenerationProvider>()
    private val diagnosticReader = mockk<ListDiagnosticReader>()
    private val diagnosticAuditWriter = mockk<ListDiagnosticAuditWriter>()
    private val properties = FeedbackListReadProperties()
    private val criteriaFactory = FeedbackListCriteriaFactory(permissions)
    private val snapshotProvider = FeedbackListSnapshotProvider(properties, generationProvider)
    private val pageAssembler = FeedbackListPageAssembler(
        repository, FeedbackListAssembler(jacksonObjectMapper()), diagnosticReader
    )
    private val snapshotReader = FeedbackListSnapshotReader(
        repository, criteriaFactory, snapshotProvider, pageAssembler
    )
    private val service = FeedbackListApplicationService(
        principal, criteriaFactory, snapshotReader, diagnosticAuditWriter
    )
    private val exports = FeedbackExportApplicationService(
        repository, principal, criteriaFactory, snapshotProvider, FeedbackCsvExporter(privacy, audits)
    )

    init {
        every { principal.require() } returns actor()
        every { diagnosticReader.read(any(), any()) } returns ListDiagnosticReadResult(emptyMap(), emptyList())
        every { diagnosticAuditWriter.append(any()) } just Runs
    }

    @Test
    fun `workspace rejection happens before read switch and generation lookup`() {
        every { principal.require() } returns actor().copy(authProvider = AuthProvider.LOCAL, permissions = emptySet())

        assertThrows(ClientException::class.java) {
            service.list(query())
        }

        verify(exactly = 0) { generationProvider.require(any()) }
        verify(exactly = 0) { repository.stats(any(), any()) }
    }

    @Test
    fun `default-disabled read fails closed before touching a generation`() {
        val error = assertThrows(ServiceException::class.java) {
            service.list(query())
        }

        assertEquals(503, error.errorCode.httpStatus.value())
        assertEquals("FEEDBACK_FEEDBACK_LIST_READ_DISABLED", error.reasonCode)
        verify(exactly = 0) { generationProvider.require(any()) }
    }

    @Test
    fun `read rejects active generation when verification facts are incomplete`() {
        properties.readEnabled = true
        every { generationProvider.require(ProjectionCapability.LIST) } throws
            ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "FEEDBACK_FEEDBACK_LIST_GENERATION_INCOMPLETE")

        val error = assertThrows(ServiceException::class.java) { service.list(query()) }

        assertEquals(503, error.errorCode.httpStatus.value())
        assertEquals("FEEDBACK_FEEDBACK_LIST_GENERATION_INCOMPLETE", error.reasonCode)
        verify(exactly = 0) { repository.stats(any(), any()) }
    }

    @Test
    fun `read rejects missing or duplicate active generations with 503`() {
        properties.readEnabled = true
        every { generationProvider.require(ProjectionCapability.LIST) } throws
            ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "FEEDBACK_FEEDBACK_LIST_ACTIVE_GENERATION_INVALID")

        repeat(2) {
            val error = assertThrows(ServiceException::class.java) { service.list(query()) }
            assertEquals(503, error.errorCode.httpStatus.value())
            assertEquals("FEEDBACK_FEEDBACK_LIST_ACTIVE_GENERATION_INVALID", error.reasonCode)
        }
        verify(exactly = 0) { repository.stats(any(), any()) }
    }

    @Test
    fun `list pins one verified generation and batch resolves current-page owners`() {
        properties.readEnabled = true
        val row = projection(11, ownerId = 9)
        every { generationProvider.require(ProjectionCapability.LIST) } returns activeGeneration()
        every { permissions.snapshot() } returns DataPermissionSnapshot(DataAccessScope(setOf("SDK"), null, null), DataFieldPolicy(true, emptySet(), emptySet()))
        every { principal.require() } returns actor()
        every { repository.stats(7, any()) } returns FeedbackListStats(total = 1, newRecords5m = 1)
        every { repository.page(7, any(), 20, 0) } returns listOf(row)
        every { repository.owners(listOf(9)) } returns mapOf(9L to "负责人甲")
        val diagnosticAudit = ListDiagnosticAudit(5, 11, listOf("DEVICE_SN"), "RAW")
        every { diagnosticReader.read(listOf(row), any()) } returns ListDiagnosticReadResult(
            mapOf(11L to ListDiagnosticValues(emptyList(), "RAW")),
            listOf(diagnosticAudit)
        )

        val result = service.list(query())

        assertEquals(1, result.total)
        assertEquals("负责人甲", result.records.single().ownerName)
        verifyOrder {
            generationProvider.require(ProjectionCapability.LIST)
            repository.stats(7, any())
            repository.page(7, any(), 20, 0)
            repository.owners(listOf(9))
            diagnosticReader.read(listOf(row), any())
            diagnosticAuditWriter.append(listOf(diagnosticAudit))
        }
    }

    @Test
    fun `list normalizes workflow aliases and keeps common and channel keywords independent`() {
        properties.readEnabled = true
        val captured = slot<FeedbackListCriteria>()
        every { generationProvider.require(ProjectionCapability.LIST) } returns activeGeneration()
        every { permissions.snapshot() } returns DataPermissionSnapshot(DataAccessScope(setOf("SDK", "REDBLACK"), null, null), DataFieldPolicy(true, emptySet(), emptySet()))
        every { repository.stats(7, capture(captured)) } returns FeedbackListStats(total = 0)
        every { repository.owners(emptyList()) } returns emptyMap()

        service.list(query().copy(
            common = query().common.copy(keyword = "common", statuses = listOf("OPEN", "IN_PROGRESS")),
            redblack = RedblackFeedbackFilters(channelKeyword = "redblack")
        ))

        assertEquals("common", captured.captured.keyword)
        assertEquals("redblack", captured.captured.channelKeyword)
        assertEquals(listOf("NEW", "PROCESSING"), captured.captured.statuses)
        verify(exactly = 0) { repository.page(any(), any(), any(), any()) }
    }

    @Test
    fun `invalid pagination and sort fail before projection queries`() {
        properties.readEnabled = true

        listOf(
            query().copy(page = FeedbackPageRequest(0, 20)),
            query().copy(page = FeedbackPageRequest(1, 101)),
            query().copy(sort = FeedbackSortSpec("unsafeColumn", "desc")),
            query().copy(sort = FeedbackSortSpec("reportedAt", "sideways"))
        ).forEach { invalid ->
            assertThrows(ClientException::class.java) { service.list(invalid) }
        }

        verify(exactly = 0) { generationProvider.require(any()) }
        verify(exactly = 0) { repository.stats(any(), any()) }
    }

    @Test
    fun `query export reads at most 5001 rows and rejects the sentinel`() {
        properties.readEnabled = true
        every { generationProvider.require(ProjectionCapability.LIST) } returns activeGeneration()
        every { permissions.snapshot() } returns DataPermissionSnapshot(DataAccessScope(setOf("SDK"), null, null), DataFieldPolicy(true, emptySet(), emptySet()))
        every { principal.require() } returns actor()
        every { repository.exportRows(7, any(), 5001) } returns (1L..5001L).map { projection(it) }

        assertThrows(ClientException::class.java) {
            exports.exportQuery(query(), auditContext())
        }
        verify(exactly = 0) { privacy.requireSafe(any(), any(), *anyVararg()) }
        verify(exactly = 0) { audits.enqueue(any()) }
    }

    @Test
    fun `explicit export batches ids by 200 then gates privacy and enqueues audit`() {
        properties.readEnabled = true
        val ids = (1L..201L).toList()
        every { generationProvider.require(ProjectionCapability.LIST) } returns activeGeneration()
        every { permissions.snapshot() } returns DataPermissionSnapshot(DataAccessScope(setOf("SDK"), null, null), DataFieldPolicy(true, emptySet(), emptySet()))
        every { principal.require() } returns actor()
        every { repository.rowsByIds(7, any(), any()) } answers {
            thirdArg<List<Long>>().map { projection(it) }
        }
        every { privacy.requireSafe(any(), any(), *anyVararg()) } just Runs
        every { audits.enqueue(any()) } just Runs

        val csv = exports.exportSelection(ids, auditContext())

        assertEquals(202, csv.lineSequence().count())
        verify(exactly = 1) { repository.rowsByIds(7, any(), ids.take(200)) }
        verify(exactly = 1) { repository.rowsByIds(7, any(), ids.drop(200)) }
        verifyOrder {
            privacy.requireSafe("FEEDBACK_EXPORT", 1, any(), any())
            audits.enqueue(any())
        }
    }

    private fun query() = FeedbackListQuery(
        common = CommonFeedbackFilters(channel = "SDK"),
        page = FeedbackPageRequest(1, 20)
    )

    private fun activeGeneration() = FeedbackListProjectionGenerationPo(
        id = 7,
        generationKey = "active-7",
        generationStatus = "ACTIVE",
        buildCursorSourceId = 20,
        buildFenceSourceId = 20,
        expectedSourceCount = 20,
        projectedSourceCount = 19,
        rejectedSourceCount = 1,
        contentChecksum = "a".repeat(64),
        verifiedMutationEpoch = 12,
        verifiedMemberChecksum = "b".repeat(64),
        verifiedAt = LocalDateTime.of(2026, 8, 30, 9, 0),
        cutoverMutationEpoch = 12,
        activatedBy = 5,
        activatedAt = LocalDateTime.of(2026, 8, 30, 9, 1)
    )

    private fun projection(id: Long, ownerId: Long? = null) = FeedbackListRecord(
        sourceId = id,
        sourceChannel = "SDK",
        sourceRecordId = "SDK-$id",
        title = "安全标题$id",
        description = "安全正文$id",
        processingStatus = "NEW",
        distributionStatus = if (ownerId == null) "UNASSIGNED" else "ACCEPTED",
        ownerUserId = ownerId,
        reportedAt = LocalDateTime.of(2026, 8, 30, 10, 0),
        ingestTime = LocalDateTime.of(2026, 8, 30, 10, 1)
    )

    private fun actor() = WebPrincipal(
        5, "phase", "", "反馈用户", setOf("PHASE_ONE_TECHNICAL"),
        setOf("access:phase_one_workspace"), authProvider = "SSO"
    )

    private fun auditContext() = FeedbackExportAuditContext("request-123456", "127.0.0.1")
}
