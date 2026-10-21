package com.feedback.repository.report

import org.springframework.stereotype.Repository

/** 对 Service 保持稳定的报告任务边界；具体职责由三个窄 Repository 承担。 */
@Repository
class ReportTaskRepository(
    private val lifecycle: ReportTaskLifecycleRepository,
    private val cleanup: ReportArtifactCleanupRepository,
    private val principals: ReportPrincipalRepository
) {
    fun create(requestSnapshot: String, actor: Long?, source: String) = lifecycle.create(requestSnapshot, actor, source)
    fun claim(id: Long, holder: String) = lifecycle.claim(id, holder)
    fun lock(id: Long) = lifecycle.lock(id)
    fun cancelCompletedReport(taskId: Long, reportId: Long) {
        cleanup.delete(reportId)
        lifecycle.markCancelled(taskId)
    }
    fun complete(id: Long, reportId: Long) = lifecycle.complete(id, reportId)
    fun failed(id: Long, error: Exception, holder: String) = lifecycle.failed(id, error, holder)
    fun retryable(id: Long) = lifecycle.retryable(id)
    fun queueRetry(id: Long, actor: Long?) = lifecycle.queueRetry(id, actor)
    fun view(id: Long, actor: Long?) = lifecycle.view(id, actor)
    fun list(actor: Long?) = lifecycle.list(actor)
    fun due() = lifecycle.due()
    fun cancel(id: Long, actor: Long?) = lifecycle.cancel(id, actor)
    fun recoverExpired() = lifecycle.recoverExpired()
    fun principal(actorId: Long) = principals.principal(actorId)
}
