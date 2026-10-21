package com.feedback.repository.report

import com.feedback.model.po.report.ReportIdempotencyInsertPo
import com.feedback.mapper.report.ReportIdempotencyMapper
import com.feedback.mapper.report.ReportLineageMapper
import org.springframework.stereotype.Repository

/** 报告幂等占位与再生成谱系的单表持久化边界。 */
@Repository
class ReportIdempotencyRepository(
    private val tasks: ReportIdempotencyMapper,
    private val reports: ReportLineageMapper,
) {
    fun hasCompletedBase(base: String) = tasks.completedBase(base) > 0
    fun compatible(key: String) = tasks.compatible(key)
    fun insert(key: String, source: String, base: String, regeneration: Int) {
        if(tasks.insert(ReportIdempotencyInsertPo(key, source, base, regeneration)) != 1) reportRepositoryFailed("REPORT_IDEMPOTENCY_INSERT_FAILED")
    }
    fun byHash(key: String) = tasks.byHash(key)
    fun nextRegeneration(base: String) = tasks.regenerationNumbers(base).maxOrNull()?.plus(1) ?: 1
    fun complete(key: String, reportId: Long) {
        if(tasks.complete(key, reportId) != 1) reportRepositoryFailed("REPORT_IDEMPOTENCY_COMPLETE_FAILED")
    }
    fun setLineage(reportId: Long, key: String, base: String, regeneration: Int) {
        if (regeneration == 0) {
            if(reports.setInitial(reportId, key) != 1) reportRepositoryFailed("REPORT_LINEAGE_UPDATE_FAILED")
        } else {
            val previous = (tasks.previous(base, regeneration - 1) ?: reportRepositoryFailed("REPORT_PREVIOUS_GENERATION_NOT_FOUND")).reportId
            if(reports.setRegenerated(reportId, key, previous, regeneration) != 1) reportRepositoryFailed("REPORT_LINEAGE_UPDATE_FAILED")
        }
    }
}
