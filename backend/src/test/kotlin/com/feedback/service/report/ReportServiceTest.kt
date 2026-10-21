package com.feedback.service.report

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.report.ReportDeletePo
import com.feedback.model.po.report.ReportDetailPo
import com.feedback.model.po.report.ReportInsertPo
import com.feedback.model.po.report.ReportMetadataUpdatePo
import com.feedback.repository.report.ReportRepository
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import com.feedback.service.ai.LlmClient
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.LocalDate

class ReportServiceTest {
    private val repository = mockk<ReportRepository>()
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val permissions = mockk<DataPermissionService>()
    private val llm = mockk<LlmClient>()
    private val accessPolicy = mockk<ReportAccessPolicy>()
    private val narrations = mockk<ReportNarrationService>()
    private val facts = mockk<SixReportFactsService>()
    private val governance = mockk<ReportGovernanceService>()
    private val service = ReportService(
        repository, mapper, permissions, llm, accessPolicy, narrations,
        ReportNumberValidator(), facts, governance,
    )

    @AfterEach
    fun clearSecurity() = SecurityContextHolder.clearContext()

    @Test
    fun `generate preserves governed facts fallback metadata and final detail response`() {
        val day = LocalDate.now().minusDays(1)
        every { facts.collect("DAILY_INGEST", day, day, any()) } returns
            ReportStats(12, 3, null, mapOf("overviewMetrics" to mapOf("value" to 12)))
        every { governance.freshness(any()) } returns mapOf("overallStatus" to "FRESH")
        every { narrations.generate(any(), "DAILY_INGEST", any()) } returns
            ReportNarrationResult(null, null, true, "REPORT_PROMPT_UNAVAILABLE")
        every { permissions.scope() } returns DataAccessScope(setOf("SDK"), null, null)
        every { repository.insert(any()) } answers {
            val row = firstArg<ReportInsertPo>()
            assertEquals(12, row.rawRecordCount)
            assertEquals("PUBLISHED", row.status)
            9
        }
        every { repository.updateMetadata(any()) } just runs
        every { repository.detail(9) } returns ReportDetailPo(
            id = 9,
            type = "DAILY_INGEST",
            title = "每日数据接入报告",
            contentJson = "{}",
            factsJson = "{}",
            freshnessJson = "{}",
            validationErrors = "[]",
            modelConfig = "{}",
            scopeSnapshot = "{\"channels\":[\"SDK\"]}",
        )
        every { accessPolicy.canView("DAILY_INGEST", any()) } returns true
        every { governance.audit(any(), any(), any(), any(), any()) } just runs

        val result = service.generate(GenerateReportRequest("DAILY_INGEST", day, day), 7)

        assertEquals(9L, result["id"])
        assertTrue(result.containsKey("facts"))
        verify { repository.updateMetadata(match<ReportMetadataUpdatePo> { it.id == 9L && it.freshnessStatus == "FRESH" }) }
    }

    @Test
    fun `delete keeps admin permission scope lock and hard deletion audit`() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            WebPrincipal(7, "admin", "", "管理员", setOf("ADMIN"), setOf("action:delete_reports")),
            "",
        )
        every { repository.lockForDelete(9) } returns ReportDeletePo(9, "RISK_BRIEF", "风险简报", "{}")
        every { accessPolicy.canView("RISK_BRIEF", "{}") } returns true
        every { repository.hardDelete(9) } just runs
        every { governance.audit(any(), any(), any(), any(), any()) } just runs

        val result = service.delete(9, 7)

        assertEquals(true, result["deleted"])
        assertEquals("HARD_DELETE", result["deletionMode"])
        verify { repository.hardDelete(9) }
        verify { governance.audit("DELETE_REPORT", "SUCCESS", 7, "9", match { it["riskDeliveryEvidenceDeleted"] == true }) }
    }
}
