package com.feedback.service.report

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.report.ReportTaskDuePo
import com.feedback.security.WebPrincipal
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder

class ReportGenerationOrchestratorTest {
    @Test
    fun `worker restores actor security context and completes the leased task`() {
        val coordinator = mockk<ReportGenerationCoordinator>()
        val tasks = mockk<ReportTaskLifecycle>()
        val governance = mockk<ReportGovernanceService>()
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val request = GenerateReportRequest("DAILY_INGEST")
        every { tasks.due() } returns listOf(ReportTaskDuePo(5, mapper.writeValueAsString(request), "MANUAL", 7))
        every { tasks.principal(7) } returns WebPrincipal(7, "actor", "", "操作者", setOf("OPERATOR"), setOf("action:generate_reports"))
        every { tasks.running(5) } returns "lease"
        every { coordinator.generate(any(), 7, "MANUAL") } answers {
            val principal = SecurityContextHolder.getContext().authentication.principal as WebPrincipal
            assertEquals(7, principal.userId)
            mapOf("id" to 9L)
        }
        every { tasks.finish(5, 9, "lease") } returns false

        ReportGenerationOrchestrator(coordinator, tasks, mapper, governance).retryPending()

        verify { tasks.finish(5, 9, "lease") }
        verify(exactly = 0) { tasks.failedAttempt(any(), any(), any()) }
    }
}
