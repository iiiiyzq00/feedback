package com.feedback.repository.report

import com.feedback.mapper.report.*
import com.feedback.model.po.report.*
import com.feedback.model.query.report.*
import org.springframework.stereotype.Repository

data class VisibleReportPage(val records: List<ReportListPo>, val total: Long)

/** 报告单表持久化编排；权限过滤后的分页在有界游标扫描中完成。 */
@Repository
class ReportRepository(
    private val reports: ReportMapper,
    private val notifications: ReportNotificationMapper,
    private val occurrences: ReportOccurrenceMapper,
    private val tasks: ReportTaskMapper,
) {
    private val batchSize = 500

    fun visiblePage(type: String?, page: Int, size: Int, visible: (ReportListPo) -> Boolean): VisibleReportPage {
        val from = (page - 1) * size
        val selected = mutableListOf<ReportListPo>()
        var visibleCount = 0L
        var beforeTime: java.time.LocalDateTime? = null
        var beforeId: Long? = null
        while (true) {
            val rows = reports.page(type, beforeTime, beforeId, batchSize)
            if (rows.isEmpty()) break
            rows.forEach { row ->
                if (visible(row)) {
                    if (visibleCount >= from && selected.size < size) selected += row
                    visibleCount++
                }
            }
            val last = rows.last()
            beforeTime = last.generatedAt
            beforeId = last.id
            if (rows.size < batchSize) break
        }
        return VisibleReportPage(selected, visibleCount)
    }

    fun detail(id: Long) = reports.detail(id)
    fun lockForDelete(id: Long) = reports.lockForDelete(id)

    fun hardDelete(id: Long) {
        var notificationCount = 0
        var after = 0L
        while (true) {
            val ids = notifications.idsByReport(id, after, batchSize)
            if (ids.isEmpty()) break
            occurrences.deleteDeliveryAttempts(ids)
            notificationCount += ids.size
            after = ids.last()
            if (ids.size < batchSize) break
        }
        if(notifications.deleteByReport(id) != notificationCount) reportRepositoryFailed("REPORT_NOTIFICATION_DELETE_CONFLICT")
        tasks.deleteByReport(id)
        if(reports.delete(id) != 1) reportRepositoryFailed("REPORT_DELETE_FAILED")
    }

    fun insert(row: ReportInsertPo): Long {
        if(reports.insert(row) != 1) reportRepositoryFailed("REPORT_CREATE_FAILED")
        return row.id ?: reportRepositoryFailed("REPORT_ID_MISSING")
    }

    fun updateMetadata(row: ReportMetadataUpdatePo) {
        if(reports.updateMetadata(row) != 1) reportRepositoryFailed("REPORT_METADATA_UPDATE_FAILED")
    }
}
