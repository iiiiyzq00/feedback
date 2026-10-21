package com.feedback.scheduler

import com.feedback.security.WebPrincipal
import com.feedback.model.auth.RoleCode
import com.feedback.repository.scheduler.MaintenanceLockRepository
import com.feedback.service.report.GenerateReportRequest
import com.feedback.service.report.ReportGenerationOrchestrator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import java.time.LocalDate

class ReportSchedulersTest {
    @AfterEach fun clear()=SecurityContextHolder.clearContext()

    @Test fun `scheduled executor holds mysql lock and returns generated report`() {
        val locks=mockk<MaintenanceLockRepository>()
        val generation=mockk<ReportGenerationOrchestrator>()
        val request=GenerateReportRequest("DAILY_INGEST",LocalDate.of(2026,7,29),LocalDate.of(2026,7,29))
        every{locks.acquire("daily")} returns true
        every{locks.release("daily")} returns 1
        every{generation.generate(request,null,"SCHEDULED")} returns mapOf("id" to 9L)
        val result=ReportScheduledGenerationExecutor(locks,generation).generate("daily",request)
        assertEquals(9L,result?.get("id"))
        verify(exactly=1){generation.generate(request,null,"SCHEDULED")}
        verify(exactly=1){locks.release("daily")}
    }

    @Test fun `lock contention skips generation`() {
        val locks=mockk<MaintenanceLockRepository>()
        val generation=mockk<ReportGenerationOrchestrator>()
        every{locks.acquire("daily")} returns false
        val result=ReportScheduledGenerationExecutor(locks,generation).generate("daily",GenerateReportRequest("DAILY_INGEST"))
        assertNull(result)
        verify(exactly=0){generation.generate(any(),any(),any())}
    }

    @Test fun `scheduler installs explicit system administrator and restores context`() {
        val locks=mockk<MaintenanceLockRepository>()
        val executor=mockk<ReportScheduledGenerationExecutor>()
        val recorder=mockk<ReportScheduleRunRecorder>(relaxed=true)
        every{recorder.started(any(),any(),any(),any())} returnsMany listOf(1L,2L,3L)
        every{executor.generate(any(),any())} answers {
            val principal=SecurityContextHolder.getContext().authentication?.principal as WebPrincipal
            assertEquals(setOf(RoleCode.ADMIN),principal.roles)
            assertEquals("system-report-scheduler",principal.username)
            mapOf("id" to 10L)
        }
        ReportSchedulers(locks,executor,recorder).daily()
        assertNull(SecurityContextHolder.getContext().authentication)
        verify(exactly=3){executor.generate(any(),any())}
        verify(exactly=3){recorder.succeeded(any(),10L)}
    }

    @Test fun `terminal recorder conflict is not rewritten as generation failure`() {
        val executor=mockk<ReportScheduledGenerationExecutor>()
        val recorder=mockk<ReportScheduleRunRecorder>()
        every{recorder.started(any(),any(),any(),any())} returns 1L
        every{executor.generate(any(),any())} returns mapOf("id" to 10L)
        every{recorder.succeeded(1,10)} throws IllegalStateException("REPORT_SCHEDULE_SUCCESS_STATE_CONFLICT")

        assertThrows(IllegalStateException::class.java) {
            ReportSchedulers(mockk(),executor,recorder).daily()
        }

        verify(exactly=0){recorder.failed(any(),any())}
    }

}
