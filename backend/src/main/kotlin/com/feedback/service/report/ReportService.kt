package com.feedback.service.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.report.ReportDetailPo
import com.feedback.model.po.report.ReportInsertPo
import com.feedback.model.po.report.ReportListPo
import com.feedback.model.po.report.ReportMetadataUpdatePo
import com.feedback.repository.report.ReportRepository
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.PlatformPermissions
import com.feedback.model.auth.RoleCode
import com.feedback.service.ai.LlmClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

data class GenerateReportRequest(
    val type: String,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    val packageName: String? = null,
    val version: String? = null,
    val releasedAt: LocalDate? = null,
    val regenerate: Boolean = false,
)

/**
 * 报告查询、生成与删除用例。统计事实继续由 SixReportFactsService 完整生成；本服务只负责
 * 报告生命周期、权限、数字校验和持久化，不再保留未被调用的旧版重复统计 SQL。
 */
@Service
class ReportService(
    private val repository: ReportRepository,
    private val mapper: ObjectMapper,
    private val permissions: DataPermissionService,
    @Suppress("unused") private val llm: LlmClient,
    private val accessPolicy: ReportAccessPolicy,
    private val narrations: ReportNarrationService,
    private val numberValidator: ReportNumberValidator,
    private val sixReportFacts: SixReportFactsService,
    private val governance: ReportGovernanceService,
) {
    private val types = setOf(
        "DAILY_INGEST", "JIRA_IMPORT_RECONCILIATION", "WEEKLY_TREND", "VERSION_RELEASE",
        "QUALITY_LINKAGE", "REDBLACK_ANALYSIS", "RISK_BRIEF",
    )

    fun list(type: String?, page: Int, size: Int): Map<String, Any> {
        if(page < 1 || size !in 1..100) reportInvalid("REPORT_PAGE_INVALID")
        val normalized = type?.takeIf(String::isNotBlank)?.uppercase()?.also {
            if(it !in types) reportInvalid("REPORT_TYPE_INVALID")
        }
        val result = repository.visiblePage(normalized, page, size) { row ->
            accessPolicy.canView(row.type, row.scopeSnapshot)
        }
        return mapOf(
            "records" to result.records.map(::listMap),
            "total" to result.total,
            "page" to page,
            "size" to size,
            "metricVersion" to "six-report-v4",
        )
    }

    fun detail(id: Long): Map<String, Any?> {
        val row = repository.detail(id) ?: reportNotFound("REPORT_NOT_FOUND")
        if (!accessPolicy.canView(row.type, row.scopeSnapshot)) reportNotFound("REPORT_NOT_FOUND")
        return detailMap(row)
    }

    @Transactional
    fun delete(id: Long, actor: Long?): Map<String, Any> {
        val principal = principal() ?: reportUnauthorized("REPORT_AUTH_REQUIRED")
        if(RoleCode.ADMIN !in principal.roles || PlatformPermissions.DELETE_REPORTS !in principal.permissions) reportForbidden("REPORT_DELETE_FORBIDDEN")
        val deleter = actor ?: principal.userId
        val row = repository.lockForDelete(id) ?: reportNotFound("REPORT_NOT_FOUND")
        if (!accessPolicy.canView(row.type, row.scopeSnapshot)) reportNotFound("REPORT_NOT_FOUND")
        repository.hardDelete(id)
        governance.audit(
            "DELETE_REPORT",
            "SUCCESS",
            deleter,
            id.toString(),
            mapOf(
                "reportType" to row.type,
                "title" to row.title,
                "deletionMode" to "HARD_DELETE",
                "riskDeliveryEvidenceDeleted" to (row.type == "RISK_BRIEF"),
            ),
        )
        return mapOf("deleted" to true, "softDeleted" to false, "deletionMode" to "HARD_DELETE", "id" to id)
    }

    fun generate(req: GenerateReportRequest, actor: Long?, source: String = "MANUAL"): Map<String, Any?> {
        val type = req.type.uppercase().also { if(it !in types) reportInvalid("REPORT_TYPE_INVALID") }
        if (type == "VERSION_RELEASE") {
            if(req.packageName.isNullOrBlank() || req.version.isNullOrBlank() || req.releasedAt == null) reportInvalid("VERSION_RELEASE_METADATA_REQUIRED")
        }
        val (start, end) = period(type, req)
        val stats = sixReportFacts.collect(type, start, end, req)
        val freshness = governance.freshness()
        val governedFacts = LinkedHashMap(stats.content).apply { this["dataFreshness"] = freshness }
        val factsJson = mapper.writeValueAsString(governedFacts)
        val generated = narrations.generate("生成${label(type)}", type, factsJson)
        val validation = numberValidator.validate(generated.narration?.text, mapper.readTree(factsJson))
        val summary = validation.text ?: degradationMessage(generated.degradationReason)
        val degraded = generated.degraded
        val title = "${label(type)} $start${if (start == end) "" else " - $end"}${req.version?.let { " · $it" } ?: ""}"
        val scope = permissions.scope()
        val status = if (validation.status == "FAILED") "DRAFT" else "PUBLISHED"
        val id = repository.insert(
            ReportInsertPo(
                publicId = UUID.randomUUID().toString(),
                type = type,
                title = title,
                periodStart = start,
                periodEnd = end,
                dataLatestTime = stats.latest,
                rawRecordCount = stats.raw,
                uniqueProblemCount = stats.unique,
                contentJson = factsJson,
                factsJson = factsJson,
                summary = summary,
                validationStatus = validation.status,
                validationErrors = mapper.writeValueAsString(validation.errors),
                promptVersion = generated.prompt?.promptVersion ?: "sql-fallback-v1",
                skillVersion = generated.prompt?.skillVersion ?: "sql-fallback-v1",
                modelName = generated.narration?.modelName,
                degraded = degraded,
                scopeSnapshot = mapper.writeValueAsString(
                    mapOf("channels" to scope.channels, "modules" to scope.modules, "regions" to scope.regions),
                ),
                status = status,
                source = source,
                actor = actor,
            ),
        )
        repository.updateMetadata(
            ReportMetadataUpdatePo(
                id = id,
                promptSha256 = generated.prompt?.contentSha256,
                modelConfig = mapper.writeValueAsString(generated.prompt?.modelConfig ?: mapper.createObjectNode()),
                providerRequestId = generated.narration?.providerRequestId,
                inputTokens = generated.narration?.inputTokens,
                outputTokens = generated.narration?.outputTokens,
                latencyMs = generated.narration?.latencyMs,
                estimatedCostUsd = generated.narration?.estimatedCostUsd,
                freshnessStatus = freshness["overallStatus"]?.toString(),
                freshnessJson = mapper.writeValueAsString(freshness),
            ),
        )
        governance.audit(
            if (req.regenerate) "REGENERATE_REPORT" else "GENERATE_REPORT",
            "SUCCESS",
            actor,
            id.toString(),
            mapOf(
                "reportType" to type,
                "source" to source,
                "periodStart" to start.toString(),
                "periodEnd" to end.toString(),
                "validationStatus" to validation.status,
                "degraded" to degraded,
                "freshnessStatus" to freshness["overallStatus"],
            ),
        )
        return detail(id)
    }

    private fun listMap(row: ReportListPo): Map<String, Any?> = linkedMapOf(
        "id" to row.id,
        "type" to row.type,
        "title" to row.title,
        "periodStart" to row.periodStart,
        "periodEnd" to row.periodEnd,
        "status" to row.status,
        "generatedAt" to row.generatedAt,
        "dataLatestTime" to row.dataLatestTime,
        "freshnessStatus" to row.freshnessStatus,
        "rawRecordCount" to row.rawRecordCount,
        "uniqueProblemCount" to row.uniqueProblemCount,
        "generationSource" to row.generationSource,
        "validationStatus" to row.validationStatus,
        "degraded" to row.degraded,
        "overviewMetrics" to node(row.overviewMetrics),
    )

    private fun detailMap(row: ReportDetailPo): Map<String, Any?> = linkedMapOf(
        "id" to row.id,
        "taskId" to row.taskId,
        "type" to row.type,
        "title" to row.title,
        "periodStart" to row.periodStart,
        "periodEnd" to row.periodEnd,
        "status" to row.status,
        "generatedAt" to row.generatedAt,
        "dataLatestTime" to row.dataLatestTime,
        "freshnessStatus" to row.freshnessStatus,
        "rawRecordCount" to row.rawRecordCount,
        "uniqueProblemCount" to row.uniqueProblemCount,
        "generationSource" to row.generationSource,
        "generatedSummary" to row.generatedSummary,
        "validationStatus" to row.validationStatus,
        "promptVersion" to row.promptVersion,
        "promptSha256" to row.promptSha256,
        "skillVersion" to row.skillVersion,
        "modelName" to row.modelName,
        "providerRequestId" to row.providerRequestId,
        "inputTokens" to row.inputTokens,
        "outputTokens" to row.outputTokens,
        "latencyMs" to row.latencyMs,
        "estimatedCostUsd" to row.estimatedCostUsd,
        "degraded" to row.degraded,
        "content" to node(row.contentJson),
        "facts" to node(row.factsJson),
        "freshness" to node(row.freshnessJson),
        "validationErrors" to node(row.validationErrors),
        "modelConfig" to node(row.modelConfig),
        "scope" to node(row.scopeSnapshot),
    )

    private fun period(type: String, request: GenerateReportRequest): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        val period = when (type) {
            "DAILY_INGEST", "RISK_BRIEF" ->
                (request.periodStart ?: today.minusDays(1)) to (request.periodEnd ?: request.periodStart ?: today.minusDays(1))
            "WEEKLY_TREND" ->
                (request.periodStart ?: today.minusWeeks(1).with(DayOfWeek.MONDAY)) to
                    (request.periodEnd ?: today.minusWeeks(1).with(DayOfWeek.SUNDAY))
            "VERSION_RELEASE" -> request.releasedAt!! to
                (request.periodEnd ?: request.releasedAt.plusDays(2).coerceAtMost(today))
            else -> (request.periodStart ?: today.minusDays(7)) to (request.periodEnd ?: today)
        }
        if(period.second.isBefore(period.first) || period.second.isAfter(today)) reportInvalid("REPORT_PERIOD_INVALID")
        return period
    }

    private fun degradationMessage(reason: String?) = when (reason) {
        "REPORT_PROMPT_UNAVAILABLE" -> "AI文字解读未生成：报告生成模板尚未配置，本报告仅展示已校验的统计数据。"
        "CIRCUIT_OPEN" -> "AI文字解读暂缓：模型服务连续失败后进入保护状态，本报告仅展示已校验的统计数据。"
        else -> "AI文字解读生成失败：模型服务调用异常，本报告仅展示已校验的统计数据。"
    }

    private fun node(value: String?) = value?.let { mapper.readTree(it) }
    private fun principal() = SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal
    private fun label(type: String) = mapOf(
        "DAILY_INGEST" to "每日数据接入报告",
        "JIRA_IMPORT_RECONCILIATION" to "Jira 数据接入与对账报告",
        "WEEKLY_TREND" to "每周多渠道问题趋势报告",
        "VERSION_RELEASE" to "版本上线观察报告",
        "QUALITY_LINKAGE" to "质量问题与用户反馈关联报告",
        "REDBLACK_ANALYSIS" to "红黑榜分析报告",
        "RISK_BRIEF" to "风险与舆情简报",
    ).getValue(type)
}
