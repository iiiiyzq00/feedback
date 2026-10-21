package com.feedback.service.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.SafeErrorDetail
import com.feedback.model.po.report.ReportTaskViewPo
import com.feedback.repository.report.ReportTaskRepository
import com.feedback.security.WebPrincipal
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 报告生成任务编排；外部调用只入队，工作线程按租约恢复原操作者权限后执行。 */
@Service
class ReportGenerationOrchestrator(
    private val coordinator: ReportGenerationCoordinator,
    private val tasks: ReportTaskLifecycle,
    private val mapper: ObjectMapper,
    private val governance: ReportGovernanceService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun generate(request: GenerateReportRequest, actor: Long?, source: String = "MANUAL"): Map<String, Any?> {
        val taskId = tasks.create(request, actor, source)
        return tasks.view(taskId, actor)
    }

    fun retry(taskId: Long, actor: Long?): Map<String, Any?> {
        tasks.retryable(taskId, actor)
        return tasks.queueRetry(taskId, actor)
    }

    fun task(id: Long, actor: Long?) = tasks.view(id, actor)
    fun taskList(actor: Long?) = tasks.list(actor)
    fun cancel(id: Long, actor: Long?) = tasks.cancel(id, actor)

    fun retryPending() {
        tasks.due().forEach { row ->
            runCatching {
                asActor(row.actorId) {
                    val request = mapper.readValue(row.requestSnapshot, GenerateReportRequest::class.java)
                    execute(row.id, request, row.actorId, row.generationSource)
                }
            }.onFailure { error ->
                log.error("Report generation task failed: taskId={}, errorType={}", row.id, error.javaClass.simpleName, error)
            }
        }
    }

    private fun execute(taskId: Long, request: GenerateReportRequest, actor: Long?, source: String): Map<String, Any?> {
        val lease = tasks.running(taskId)
        return try {
            val result = coordinator.generate(request, actor, source)
            val reportId = (result["id"] as Number).toLong()
            val cancelled = tasks.finish(taskId, reportId, lease)
            if (cancelled) tasks.view(taskId, actor) else result + mapOf("taskId" to taskId, "taskStatus" to "COMPLETED")
        } catch (error: Exception) {
            tasks.failedAttempt(taskId, error, lease)
            runCatching {
                governance.audit(
                    "REPORT_GENERATION_FAILED",
                    "FAILED",
                    actor,
                    taskId.toString(),
                    mapOf("reportType" to request.type, "source" to source, "errorCode" to SafeErrorDetail.code(error)),
                )
            }
            throw error
        }
    }

    private fun <T> asActor(actor: Long?, action: () -> T): T {
        val previous = SecurityContextHolder.getContext()
        val context = SecurityContextHolder.createEmptyContext()
        val principal = actor?.let(tasks::principal) ?: WebPrincipal(
            0,
            "system-report-worker",
            "",
            "System Report Worker",
            setOf("ADMIN"),
            emptySet(),
        )
        context.authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities)
        SecurityContextHolder.setContext(context)
        return try {
            action()
        } finally {
            SecurityContextHolder.setContext(previous)
        }
    }
}

@Component
class ReportGenerationScheduler(private val orchestrator: ReportGenerationOrchestrator) {
    fun retryPending() = orchestrator.retryPending()
}

/** 报告任务状态机；每个状态修改保持独立新事务，防止报告生成事务污染租约恢复。 */
@Service
class ReportTaskLifecycle(
    private val repository: ReportTaskRepository,
    private val mapper: ObjectMapper,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun create(request: GenerateReportRequest, actor: Long?, source: String): Long =
        repository.create(mapper.writeValueAsString(request), actor, source)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun running(id: Long): String {
        val holder = "report-${UUID.randomUUID()}"
        if(repository.claim(id, holder) != 1) reportFailed("REPORT_TASK_NOT_CLAIMABLE")
        return holder
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun finish(id: Long, reportId: Long, holder: String): Boolean {
        val row = repository.lock(id) ?: reportNotFound("REPORT_TASK_NOT_FOUND")
        if(row.status != "RUNNING" || row.leaseHolder != holder) reportFailed("REPORT_TASK_LEASE_LOST")
        if (row.cancelRequested) {
            repository.cancelCompletedReport(id, reportId)
            return true
        }
        repository.complete(id, reportId)
        return false
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failedAttempt(id: Long, error: Exception, holder: String) = repository.failed(id, error, holder)

    fun retryable(id: Long, actor: Long?): Map<String, Any?> {
        val row = repository.retryable(id) ?: reportNotFound("REPORT_TASK_NOT_FOUND")
        if(row.status != "RETRY_PENDING") reportInvalid("REPORT_TASK_NOT_RETRYABLE")
        if(actor != null && row.actorId != actor) reportForbidden("REPORT_TASK_FORBIDDEN")
        return viewMap(row)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun queueRetry(id: Long, actor: Long?): Map<String, Any?> {
        if(repository.queueRetry(id, actor) != 1) reportInvalid("REPORT_TASK_NOT_RETRYABLE")
        return view(id, actor)
    }

    fun view(id: Long, actor: Long?): Map<String, Any?> =
        repository.view(id, actor)?.let(::viewMap) ?: reportNotFound("REPORT_TASK_NOT_FOUND")

    fun list(actor: Long?): List<Map<String, Any?>> = repository.list(actor).map(::viewMap)
    fun due() = repository.due()

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun cancel(id: Long, actor: Long?): Map<String, Any?> {
        if(repository.cancel(id, actor) != 1) reportInvalid("REPORT_TASK_NOT_CANCELLABLE")
        return view(id, actor)
    }

    fun recoverExpired() {
        repository.recoverExpired()
    }

    fun principal(actor: Long) = repository.principal(actor)

    private fun viewMap(row: ReportTaskViewPo): Map<String, Any?> = linkedMapOf(
        "id" to row.id,
        "publicId" to row.publicId,
        "reportType" to row.reportType,
        "generationSource" to row.generationSource,
        "status" to row.status,
        "cancelRequested" to row.cancelRequested,
        "retryCount" to row.retryCount,
        "maxRetries" to row.maxRetries,
        "reportId" to row.reportId,
        "errorCode" to row.errorCode,
        "errorDetail" to row.errorDetail,
        "createdAt" to row.createdAt,
        "startedAt" to row.startedAt,
        "completedAt" to row.completedAt,
    )
}
