package com.feedback.repository.report

import com.feedback.common.SafeErrorDetail
import com.feedback.mapper.report.ReportGenerationTaskMapper
import com.feedback.mapper.report.ReportTaskReportMapper
import com.feedback.model.po.report.ReportTaskInsertPo
import org.springframework.stereotype.Repository
import java.util.UUID

/** 报告生成任务的生命周期与报告关联。 */
@Repository
class ReportTaskLifecycleRepository(
    private val tasks: ReportGenerationTaskMapper,
    private val reports: ReportTaskReportMapper
) {
    fun create(requestSnapshot: String, actor: Long?, source: String): Long {
        val row = ReportTaskInsertPo(UUID.randomUUID().toString(), source, actor, requestSnapshot)
        if(tasks.insert(row) != 1) reportRepositoryFailed("REPORT_TASK_CREATE_FAILED")
        return row.id ?: reportRepositoryFailed("REPORT_TASK_ID_MISSING")
    }
    fun claim(id: Long, holder: String) = tasks.claim(id, holder)
    fun lock(id: Long) = tasks.lock(id)
    fun complete(id: Long, reportId: Long) {
        if(reports.attach(id, reportId) != 1) reportRepositoryFailed("REPORT_TASK_ATTACHMENT_CONFLICT")
        if(tasks.complete(id, reportId) != 1) reportRepositoryFailed("REPORT_TASK_COMPLETE_FAILED")
    }
    fun failed(id: Long, error: Exception, holder: String) {
        if(tasks.failed(id, holder, SafeErrorDetail.code(error), SafeErrorDetail.summary(error, "REPORT_GENERATION")) != 1) reportRepositoryFailed("REPORT_TASK_FAILURE_STATE_CONFLICT")
    }
    fun retryable(id: Long) = tasks.retryable(id)
    fun queueRetry(id: Long, actor: Long?) = tasks.queueRetry(id, actor)
    fun view(id: Long, actor: Long?) = tasks.view(id, actor)
    fun list(actor: Long?) = tasks.list(actor)
    fun due() = tasks.due()
    fun cancel(id: Long, actor: Long?) = tasks.cancel(id, actor)
    fun recoverExpired() = tasks.recoverExpired()
    fun markCancelled(id: Long) { if(tasks.markCancelled(id) != 1) reportRepositoryFailed("REPORT_TASK_CANCEL_FAILED") }
}
