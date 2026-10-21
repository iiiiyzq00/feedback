package com.feedback.repository.report

import com.feedback.mapper.report.ReportMapper
import com.feedback.mapper.report.ReportNotificationMapper
import com.feedback.mapper.report.ReportOccurrenceMapper
import com.feedback.mapper.report.ReportSupportTaskMapper
import org.springframework.stereotype.Repository

/** 已生成报告及其通知、投递尝试和支持任务的有序清理。 */
@Repository
class ReportArtifactCleanupRepository(
    private val notifications: ReportNotificationMapper,
    private val occurrences: ReportOccurrenceMapper,
    private val supportTasks: ReportSupportTaskMapper,
    private val reports: ReportMapper
) {
    fun delete(reportId: Long) {
        var notificationCount = 0
        var after = 0L
        while (true) {
            val ids = notifications.idsByReport(reportId, after, CLEANUP_BATCH_SIZE)
            if (ids.isEmpty()) break
            occurrences.deleteDeliveryAttempts(ids)
            notificationCount += ids.size
            after = ids.last()
            if (ids.size < CLEANUP_BATCH_SIZE) break
        }
        if(notifications.deleteByReport(reportId) != notificationCount) reportRepositoryFailed("REPORT_NOTIFICATION_DELETE_CONFLICT")
        supportTasks.deleteByReport(reportId)
        if(reports.delete(reportId) != 1) reportRepositoryFailed("REPORT_DELETE_FAILED")
    }

    private companion object { const val CLEANUP_BATCH_SIZE = 500 }
}
