package com.feedback.repository.scheduler

import com.feedback.common.SafeErrorDetail
import com.feedback.mapper.scheduler.*
import com.feedback.model.po.scheduler.*
import org.springframework.stereotype.Repository
import java.time.LocalDate

/** 定时报表运行事实持久化边界。 */
@Repository
class ReportSchedulerRepository(
    private val runs: ReportScheduleRunMapper,
) {
    fun startRun(key: String, type: String, start: LocalDate, end: LocalDate): Long {
        val row = ReportScheduleRunInsertPo(key, type, start, end)
        check(runs.insert(row) == 1) { "REPORT_SCHEDULE_RUN_CREATE_FAILED" }
        return checkNotNull(row.id) { "REPORT_SCHEDULE_RUN_ID_MISSING" }
    }
    fun succeeded(id: Long, reportId: Long) {
        check(runs.succeeded(id, reportId) == 1) { "REPORT_SCHEDULE_SUCCESS_STATE_CONFLICT" }
    }

    fun skipped(id: Long) {
        check(runs.skipped(id) == 1) { "REPORT_SCHEDULE_SKIP_STATE_CONFLICT" }
    }

    fun failed(id: Long, error: Exception) {
        check(runs.failed(
            id,
            SafeErrorDetail.code(error),
            SafeErrorDetail.summary(error, "REPORT_SCHEDULE"),
        ) == 1) { "REPORT_SCHEDULE_FAILURE_STATE_CONFLICT" }
    }
}
