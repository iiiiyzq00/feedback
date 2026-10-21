package com.feedback.service.report

import com.feedback.common.exception.ClientException
import com.feedback.model.po.report.ReportIdempotencyResultPo
import com.feedback.repository.report.ReportIdempotencyRepository
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import java.time.LocalDate

class ReportGenerationCoordinatorTest {
    private val repository = mockk<ReportIdempotencyRepository>()
    private val reports = mockk<ReportService>()
    private val permissions = mockk<DataPermissionService>()
    private val accessPolicy = mockk<ReportAccessPolicy>()
    private val coordinator = ReportGenerationCoordinator(repository, reports, permissions, accessPolicy)

    @Test
    fun `compatible completed task returns the existing visible report`() {
        val day = LocalDate.now().minusDays(1)
        every { accessPolicy.requireGenerate("DAILY_INGEST") } just runs
        every { permissions.scope() } returns DataAccessScope(setOf("SDK"), null, null)
        every { repository.hasCompletedBase(any()) } returns false
        every { repository.compatible(any()) } returns ReportIdempotencyResultPo(9)
        every { reports.detail(9) } returns mapOf("id" to 9L, "type" to "DAILY_INGEST")

        val result = coordinator.generate(GenerateReportRequest("daily_ingest", day, day), 7)

        assertEquals(true, result["idempotent"])
        verify(exactly = 0) { repository.insert(any(), any(), any(), any()) }
        verify(exactly = 0) { reports.generate(any(), any(), any()) }
    }

    @Test
    fun `fresh generation completes placeholder and stores initial lineage`() {
        val day = LocalDate.now().minusDays(1)
        every { accessPolicy.requireGenerate("DAILY_INGEST") } just runs
        every { permissions.scope() } returns DataAccessScope(setOf("SDK"), null, null)
        every { repository.hasCompletedBase(any()) } returns false
        every { repository.compatible(any()) } returns null
        every { repository.insert(any(), "MANUAL", any(), 0) } just runs
        every { reports.generate(any(), 7, "MANUAL") } returns mapOf("id" to 10L)
        every { repository.complete(any(), 10) } just runs
        every { repository.setLineage(10, any(), any(), 0) } just runs

        val result = coordinator.generate(GenerateReportRequest("DAILY_INGEST", day, day), 7)

        assertEquals(false, result["idempotent"])
        assertEquals(0, result["regenerationNo"])
        verify { repository.complete(any(), 10) }
        verify { repository.setLineage(10, any(), any(), 0) }
    }

    @Test
    fun `duplicate placeholder without result remains in progress`() {
        every { accessPolicy.requireGenerate("RISK_BRIEF") } just runs
        every { permissions.scope() } returns DataAccessScope(setOf("SDK"), null, null)
        every { repository.hasCompletedBase(any()) } returns false
        every { repository.compatible(any()) } returns null
        every { repository.insert(any(), any(), any(), any()) } throws DuplicateKeyException("race")
        every { repository.byHash(any()) } returns ReportIdempotencyResultPo(null)

        assertThrows(ClientException::class.java) {
            coordinator.generate(GenerateReportRequest("RISK_BRIEF"), 7)
        }
    }
}
