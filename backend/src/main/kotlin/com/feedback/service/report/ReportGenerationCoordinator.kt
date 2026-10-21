package com.feedback.service.report

import com.feedback.repository.report.ReportIdempotencyRepository
import com.feedback.service.auth.DataPermissionService
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 报告生成幂等与再生成谱系协调器。用户主动生成始终产生新报告；相同基础参数通过
 * regenerationNo 串联历史，并用唯一哈希占位关闭并发重复生成。
 */
@Service
class ReportGenerationCoordinator(
    private val repository: ReportIdempotencyRepository,
    private val reports: ReportService,
    private val permissions: DataPermissionService,
    private val accessPolicy: ReportAccessPolicy,
) {
    private val reportMetricVersion = "six-report-v4"

    @Transactional
    fun generate(request: GenerateReportRequest, actor: Long?, source: String = "MANUAL"): Map<String, Any?> {
        val type = request.type.uppercase()
        accessPolicy.requireGenerate(type)
        val normalized = normalize(type, request)
        val scope = permissions.scope()
        val baseKey = listOf(
            source,
            reportMetricVersion,
            type,
            normalized.periodStart,
            normalized.periodEnd,
            normalized.packageName ?: "-",
            normalized.version ?: "-",
            scope.channels.sorted().joinToString(","),
            scope.modules?.sorted()?.joinToString(",") ?: "*",
            scope.regions?.sorted()?.joinToString(",") ?: "*",
        ).joinToString(":").take(180)

        val regeneration = if (repository.hasCompletedBase(baseKey)) repository.nextRegeneration(baseKey) else 0
        val key = if (regeneration == 0) baseKey else "$baseKey:r$regeneration"

        // 历史迁移记录可能保留旧 task_key，因此先按不可变 input_snapshot 兼容查找。
        repository.compatible(key)?.let { compatible ->
            val existing = compatible.reportId ?: reportInvalid("REPORT_GENERATION_IN_PROGRESS")
            return existingResult(existing)
        }

        try {
            repository.insert(key, source, baseKey, regeneration)
        } catch (_: DuplicateKeyException) {
            val existing = repository.byHash(key)?.reportId ?: reportInvalid("REPORT_GENERATION_IN_PROGRESS")
            return existingResult(existing)
        }

        val result = reports.generate(normalized, actor, source)
        val reportId = (result["id"] as Number).toLong()
        repository.complete(key, reportId)
        repository.setLineage(reportId, key, baseKey, regeneration)
        return result + mapOf("idempotent" to false, "regenerationNo" to regeneration)
    }

    private fun existingResult(reportId: Long): Map<String, Any?> {
        val report = runCatching { reports.detail(reportId) }.getOrNull()
        if(report == null) reportFailed("REPORT_IDEMPOTENCY_EVIDENCE_CORRUPT")
        return report + mapOf("idempotent" to true)
    }

    private fun normalize(type: String, request: GenerateReportRequest): GenerateReportRequest {
        val today = LocalDate.now()
        return when (type) {
            "DAILY_INGEST", "RISK_BRIEF" -> request.copy(
                periodStart = request.periodStart ?: today.minusDays(1),
                periodEnd = request.periodEnd ?: request.periodStart ?: today.minusDays(1),
            )
            "WEEKLY_TREND" -> request.copy(
                periodStart = request.periodStart ?: today.minusWeeks(1).with(DayOfWeek.MONDAY),
                periodEnd = request.periodEnd ?: today.minusWeeks(1).with(DayOfWeek.SUNDAY),
            )
            "VERSION_RELEASE" -> request.copy(
                periodStart = request.periodStart ?: request.releasedAt,
                periodEnd = request.periodEnd ?: request.releasedAt?.plusDays(2)?.coerceAtMost(today),
            )
            else -> request.copy(
                periodStart = request.periodStart ?: today.minusDays(7),
                periodEnd = request.periodEnd ?: today,
            )
        }
    }
}
