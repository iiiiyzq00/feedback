package com.feedback.scheduler

import com.feedback.model.po.report.VersionReleaseSchedulePo
import com.feedback.repository.report.VersionReleaseScheduleRepository
import com.feedback.service.report.ReportGenerationOrchestrator
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.RoleCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.LocalDate

class VersionReleaseReportSchedulerTest {
    private val repository=mockk<VersionReleaseScheduleRepository>()
    private val generation=mockk<ReportGenerationOrchestrator>()

    @AfterEach fun clear()=SecurityContextHolder.clearContext()

    @Test fun `uses config key fallback under system principal and restores caller context`() {
        val released=LocalDate.now().minusDays(4)
        every{repository.due()} returns listOf(VersionReleaseSchedulePo("APP_VERSION:com.demo.app:2.1",null,null,released))
        every{generation.generate(any(),null,"SCHEDULED")} answers {
            val principal=SecurityContextHolder.getContext().authentication.principal as WebPrincipal
            assertEquals(setOf(RoleCode.ADMIN),principal.roles)
            emptyMap()
        }
        val previous=SecurityContextHolder.createEmptyContext()
        previous.authentication=UsernamePasswordAuthenticationToken("caller","")
        SecurityContextHolder.setContext(previous)

        VersionReleaseReportScheduler(repository,generation).due()

        verify{generation.generate(match{it.packageName=="com.demo.app"&&it.version=="2.1"&&it.periodStart==released},null,"SCHEDULED")}
        assertSame(previous,SecurityContextHolder.getContext())
    }
}
