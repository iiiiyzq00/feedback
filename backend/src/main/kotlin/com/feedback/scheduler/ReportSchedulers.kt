package com.feedback.scheduler

import com.feedback.security.WebPrincipal
import com.feedback.repository.scheduler.MaintenanceLockRepository
import com.feedback.repository.scheduler.ReportSchedulerRepository
import com.feedback.service.report.GenerateReportRequest
import com.feedback.service.report.ReportGenerationOrchestrator
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Component
class ReportSchedulers(
    private val locks: MaintenanceLockRepository,
    private val scheduledGeneration: ReportScheduledGenerationExecutor,
    private val runRecorder: ReportScheduleRunRecorder
) {
    fun daily() = asSystem {
        val day = LocalDate.now().minusDays(1)
        generateLocked("feedback_daily_ingest", GenerateReportRequest("DAILY_INGEST", day, day))
        generateLocked("feedback_jira_import_reconciliation", GenerateReportRequest("JIRA_IMPORT_RECONCILIATION", day, day))
        generateLocked("feedback_risk_brief", GenerateReportRequest("RISK_BRIEF", day, day))
    }

    fun weekly() = asSystem {
        val start = LocalDate.now().minusWeeks(1).with(java.time.DayOfWeek.MONDAY)
        val end = start.with(java.time.DayOfWeek.SUNDAY)
        generateLocked("feedback_weekly_trend", GenerateReportRequest("WEEKLY_TREND", start, end))
    }

    private fun generateLocked(name: String, request: GenerateReportRequest) {
        val start = requireNotNull(request.periodStart)
        val end = requireNotNull(request.periodEnd)
        val runId = runRecorder.started(name, request.type, start, end)
        val result = try {
            scheduledGeneration.generate(name, request)
        } catch (ex: Exception) {
            runRecorder.failed(runId, ex)
            throw ex
        }
        if (result == null) runRecorder.skipped(runId)
        else runRecorder.succeeded(runId, (result["id"] as Number).toLong())
    }

    private fun <T> asSystem(action: () -> T): T {
        val previous = SecurityContextHolder.getContext()
        val context = SecurityContextHolder.createEmptyContext()
        val principal = WebPrincipal(0, "system-report-scheduler", "", "System Report Scheduler", setOf("ADMIN"), emptySet())
        context.authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities)
        SecurityContextHolder.setContext(context)
        return try { action() } finally { SecurityContextHolder.setContext(previous) }
    }
}

@Service
class ReportScheduledGenerationExecutor(
    private val locks: MaintenanceLockRepository,
    private val generation: ReportGenerationOrchestrator
) {
    @Transactional
    fun generate(lockName:String,request:GenerateReportRequest):Map<String,Any?>? {
        if(!locks.acquire(lockName))return null
        try{return generation.generate(request,null,"SCHEDULED")}
        finally{locks.release(lockName)}
    }
}

@Service
class ReportScheduleRunRecorder(private val repository: ReportSchedulerRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun started(key:String,type:String,start:LocalDate,end:LocalDate):Long {
        return repository.startRun(key, type, start, end)
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun succeeded(id:Long,reportId:Long)=repository.succeeded(id, reportId)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun skipped(id:Long)=repository.skipped(id)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failed(id:Long,error:Exception)=repository.failed(id, error)
}
