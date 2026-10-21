package com.feedback.service.report

import com.feedback.model.po.report.*
import com.feedback.model.query.report.*
import com.feedback.repository.report.SixReportFactsRepository
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class ReportStats(
    val raw: Long,
    val unique: Long,
    val latest: LocalDateTime?,
    val content: Map<String, Any>,
)

object ReportOverviewMetricDictionary {
    fun subject(type: String) = when (type) {
        "VERSION_RELEASE" -> "指定包名与版本"
        "QUALITY_LINKAGE" -> "质量平台渠道"
        "REDBLACK_ANALYSIS" -> "红黑榜渠道"
        "RISK_BRIEF" -> "风险计算范围"
        else -> "报告统计范围"
    }

    fun primaryLabel(type: String) = when (type) {
        "VERSION_RELEASE" -> "版本反馈"
        "QUALITY_LINKAGE" -> "质量样本"
        "REDBLACK_ANALYSIS" -> "红黑榜样本"
        "RISK_BRIEF" -> "风险分析反馈"
        else -> "有效反馈"
    }

    fun secondaryLabel(type: String) = when (type) {
        "VERSION_RELEASE" -> "版本主问题簇"
        "QUALITY_LINKAGE" -> "质量主问题簇"
        "REDBLACK_ANALYSIS" -> "红黑榜主问题簇"
        "RISK_BRIEF" -> "风险主问题簇"
        else -> "主问题簇"
    }
}

private enum class FactRelation { CLUSTER, IDENTITY, SDK, JIRA, REDBLACK, AI }

private data class ReportSourceFact(
    val source: ReportFactSourcePo,
    val contents: List<ReportFactContentPo>,
    val links: List<ReportFactClusterLinkPo>,
    val clusterTitles: Map<Long, String>,
    val identities: List<ReportFactIdentityPo>,
    val sdk: List<ReportFactSdkPo>,
    val jira: List<ReportFactJiraPo>,
    val redblack: List<ReportFactRedblackPo>,
    val ai: List<ReportFactAiPo>,
)

private data class ReportSubject(
    val channel: String? = null,
    val packageName: String? = null,
    val appVersion: String? = null,
) {
    fun matches(source: ReportFactSourcePo): Boolean =
        (channel == null || source.channel == channel) &&
            (packageName == null || source.packageName == packageName) &&
            (appVersion == null || source.appVersion == appVersion)

    fun description(): String = when {
        packageName != null -> "s.package_name=:overviewPackage AND s.app_version=:overviewVersion"
        channel == "QUALITY" -> "s.source_channel='QUALITY'"
        channel == "REDBLACK" -> "s.source_channel='REDBLACK'"
        channel == "JIRA" -> "s.source_channel='JIRA'"
        else -> "1=1"
    }
}

private class SummaryAccumulator(
    private val start: LocalDateTime,
    private val end: LocalDateTime,
    private val subject: ReportSubject,
) {
    var raw: Long = 0
        private set
    var clustered: Long = 0
        private set
    var latest: LocalDateTime? = null
        private set
    private val clusters = linkedSetOf<Long>()

    fun accept(fact: ReportSourceFact) {
        val occurredAt = fact.source.occurredAt ?: return
        if (occurredAt < start || occurredAt > end || !subject.matches(fact.source)) return
        raw++
        if (latest == null || occurredAt > latest) latest = occurredAt
        val main = fact.links.filter { it.relationRole == "MAIN" }.map { it.clusterId }.toSet()
        if (main.isNotEmpty()) clustered++
        clusters += main
    }

    val unique: Long get() = clusters.size.toLong()
}

private data class ClusterAggregate(
    val id: Long,
    var title: String = "",
    val sourceIds: MutableSet<Long> = linkedSetOf(),
    val channels: MutableSet<String> = linkedSetOf(),
    var severity: String? = null,
    var severeSeverity: String? = null,
    val severeChannels: MutableSet<String> = linkedSetOf(),
    var hasQuality: Boolean = false,
    var hasSdk: Boolean = false,
    var hasJira: Boolean = false,
    var hasRedblack: Boolean = false,
    var hasBlack: Boolean = false,
    var qualityFirstSeen: LocalDateTime? = null,
    var userFirstSeen: LocalDateTime? = null,
)

/**
 * 七类报告事实计算器。数据库只负责单表分页和当前页批量读取；跨表关系、窗口、TOP、
 * HAVING 与风险评分全部在代码中复算，避免 JOIN、跨表子查询和 N+1，同时保留 six-report-v4 输出合同。
 */
@Service
class SixReportFactsService(
    private val repository: SixReportFactsRepository,
    private val permissions: DataPermissionService,
) {
    fun collect(type: String, start: LocalDate, end: LocalDate, request: GenerateReportRequest): ReportStats {
        val scope = permissions.scope()
        val periodStart = start.atStartOfDay()
        val periodEnd = end.atTime(LocalTime.MAX)
        val subject = subject(type, request)
        val summary = SummaryAccumulator(periodStart, periodEnd, subject)
        val sections = when (type) {
            "DAILY_INGEST" -> daily(scope, periodStart, periodEnd, summary)
            "JIRA_IMPORT_RECONCILIATION" -> jiraReconciliation(scope, periodStart, periodEnd, summary)
            "WEEKLY_TREND" -> weekly(scope, periodStart, periodEnd, summary)
            "VERSION_RELEASE" -> version(scope, periodStart, periodEnd, request, summary)
            "QUALITY_LINKAGE" -> quality(scope, periodStart, periodEnd, summary)
            "REDBLACK_ANALYSIS" -> redblack(scope, periodStart, periodEnd, summary)
            "RISK_BRIEF" -> risk(scope, periodStart, periodEnd, summary)
            else -> reportInvalid("REPORT_TYPE_INVALID")
        }
        val secured = sections.mapValues { (_, value) ->
            if (value is List<*>) value.map { item ->
                if (item is Map<*, *>) LinkedHashMap(item).apply {
                    this["_drilldown"] = drill(item, start, end, scope)
                } else item
            } else value
        }
        val dictionary = overview(type, start, end, scope, subject, summary)
        return ReportStats(
            summary.raw,
            summary.unique,
            summary.latest,
            mapOf(
                "reportType" to type,
                "methodology" to mapOf(
                    "calculation" to "PARAMETERIZED_SQL",
                    "countingMode" to "ELIGIBLE_DISTINCT_SOURCE_AND_MAIN_CLUSTER",
                    "metricVersion" to "six-report-v4",
                ),
                "summary" to mapOf(
                    "rawRecordCount" to summary.raw,
                    "uniqueProblemCount" to summary.unique,
                    "overviewMetrics" to dictionary,
                ),
                "overviewMetrics" to dictionary,
                "summaryDrilldowns" to mapOf(
                    "rawRecordCount" to drill(emptyMap<String, Any>(), start, end, scope),
                    "uniqueProblemCount" to drill(mapOf("clustered" to true), start, end, scope),
                ),
                "sections" to secured,
            ),
        )
    }

    private fun subject(type: String, request: GenerateReportRequest) = when (type) {
        "VERSION_RELEASE" -> ReportSubject(packageName = request.packageName!!, appVersion = request.version!!)
        "QUALITY_LINKAGE" -> ReportSubject(channel = "QUALITY")
        "REDBLACK_ANALYSIS" -> ReportSubject(channel = "REDBLACK")
        "JIRA_IMPORT_RECONCILIATION" -> ReportSubject(channel = "JIRA")
        else -> ReportSubject()
    }

    private fun overview(
        type: String,
        start: LocalDate,
        end: LocalDate,
        scope: DataAccessScope,
        subject: ReportSubject,
        summary: SummaryAccumulator,
    ) = mapOf(
        "version" to "six-report-overview-v1",
        "subject" to ReportOverviewMetricDictionary.subject(type),
        "filters" to mapOf(
            "periodStart" to start.toString(),
            "periodEnd" to end.toString(),
            "channels" to scope.channels.sorted(),
            "modules" to scope.modules?.sorted(),
            "regions" to scope.regions?.sorted(),
            "reportSubject" to subject.description(),
        ),
        "primary" to mapOf(
            "code" to "eligibleFeedbackCount",
            "legacyField" to "rawRecordCount",
            "label" to ReportOverviewMetricDictionary.primaryLabel(type),
            "unit" to "条",
            "value" to summary.raw,
            "definition" to "报告主题范围内未删除、脱敏通过且业务时间位于统计周期的不同反馈记录数",
        ),
        "secondary" to mapOf(
            "code" to "mainClusterCount",
            "legacyField" to "uniqueProblemCount",
            "label" to ReportOverviewMetricDictionary.secondaryLabel(type),
            "unit" to "个",
            "value" to summary.unique,
            "definition" to "上述反馈通过有效 MAIN 关系关联的不同主问题簇数",
        ),
        "unclustered" to mapOf(
            "code" to "unclusteredFeedbackCount",
            "label" to "未归簇反馈",
            "unit" to "条",
            "value" to (summary.raw - summary.clustered).coerceAtLeast(0),
            "definition" to "主题范围内尚无有效 MAIN 归簇关系的反馈数",
        ),
    )

    private fun daily(
        scope: DataAccessScope,
        start: LocalDateTime,
        end: LocalDateTime,
        summary: SummaryAccumulator,
    ): LinkedHashMap<String, Any> {
        val previousStart = start.minusDays(1)
        val previousEnd = start.minusNanos(1)
        data class ChannelCount(var current: Long = 0, var previous: Long = 0)
        data class Quality(
            var count: Long = 0,
            var scoreSum: BigDecimal = BigDecimal.ZERO,
            var scoreCount: Long = 0,
            var complete: Long = 0,
            var mappingSuccess: Long = 0,
        )
        val ingest = linkedMapOf<String, ChannelCount>()
        val masking = linkedMapOf<String, Long>()
        val quality = linkedMapOf<String, Quality>()
        scan(scope, null, null, ReportSubject(), emptySet()) { facts ->
            facts.forEach { fact ->
                summary.accept(fact)
                val channel = ingest.getOrPut(fact.source.channel) { ChannelCount() }
                fact.contents.forEach { content ->
                    when {
                        within(fact.source.occurredAt, start, end) -> channel.current++
                        within(fact.source.occurredAt, previousStart, previousEnd) -> channel.previous++
                    }
                    if (within(fact.source.occurredAt, start, end)) {
                        masking[content.maskingStatus] = masking.getOrDefault(content.maskingStatus, 0) + 1
                        val q = quality.getOrPut(fact.source.channel) { Quality() }
                        q.count++
                        content.dataQualityScore?.let { q.scoreSum += it; q.scoreCount++ }
                        if (content.title != null && content.description != null && fact.source.occurredAt != null) q.complete++
                        if (mappingSuccess(content.mappingError)) q.mappingSuccess++
                    }
                }
            }
        }
        val batches = importBatches(start, end, null)
        val failedImports = failedImportCount(end)
        return linkedMapOf(
            "ingestOverview" to ingest.map { (channel, counts) -> row(
                "channel" to channel,
                "count" to counts.current,
                "previousCount" to counts.previous,
                "changeRate" to percentageChange(counts.current, counts.previous),
            ) },
            "importBatchStatus" to batches.sortedBy { it.createdAt }.map { batch -> row(
                "batchId" to batch.id,
                "channel" to batch.channel,
                "status" to batch.batchStatus,
                "totalRows" to batch.totalRows,
                "successRows" to batch.successRows,
                "failedRows" to batch.failedRows,
                "skippedRows" to batch.skippedRows,
                "mappingVersion" to batch.mappingVersion,
                "warning" to (batch.batchStatus in setOf("PARTIAL_SUCCESS", "FAILED") || batch.failedRows > 0),
            ) },
            "maskingStatus" to masking.map { (status, count) -> row("status" to status, "count" to count) },
            "dataQuality" to quality.map { (channel, value) ->
                val qualityRate = if (value.scoreCount == 0L) null else
                    value.scoreSum.divide(BigDecimal.valueOf(value.scoreCount), 8, RoundingMode.HALF_UP)
                        .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP)
                val completeRate = percent(value.complete, value.count)
                val mappingRate = percent(value.mappingSuccess, value.count)
                val warning = when {
                    qualityRate != null && qualityRate < BigDecimal("85") -> true
                    completeRate != null && completeRate < BigDecimal("85") -> true
                    mappingRate != null && mappingRate < BigDecimal("85") -> true
                    qualityRate == null -> null
                    else -> false
                }
                row(
                    "channel" to channel,
                    "qualityRate" to qualityRate,
                    "requiredCompletenessRate" to completeRate,
                    "mappingSuccessRate" to mappingRate,
                    "warning" to warning,
                )
            },
            "pendingTasks" to listOf(
                row("taskType" to "IMPORT_FAILED", "count" to failedImports),
                row("taskType" to "CLUSTER_REVIEW", "count" to repository.pendingAiReviews(end)),
            ),
        )
    }

    private fun jiraReconciliation(
        scope: DataAccessScope,
        start: LocalDateTime,
        end: LocalDateTime,
        summary: SummaryAccumulator,
    ): LinkedHashMap<String, Any> {
        data class JiraStatus(var sources: MutableSet<Long> = linkedSetOf(), var closed: Long = 0)
        val statuses = linkedMapOf<String, JiraStatus>()
        scan(scope, start, end, ReportSubject(channel = "JIRA"), setOf(FactRelation.JIRA)) { facts ->
            facts.forEach { fact ->
                summary.accept(fact)
                fact.jira.forEach { jira ->
                    val key = jira.status?.takeIf { it.isNotEmpty() } ?: "UNKNOWN"
                    val aggregate = statuses.getOrPut(key) { JiraStatus() }
                    aggregate.sources += fact.source.id
                    if (jira.resolvedAt != null || jira.status.orEmpty().uppercase() in CLOSED_JIRA) {
                        aggregate.closed += fact.contents.size
                    }
                }
            }
        }
        val batches = if ("JIRA" in scope.channels) importBatches(start, end, "JIRA") else emptyList()
        return linkedMapOf(
            "jiraImportBatches" to batches.sortedWith(compareByDescending<ReportFactImportBatchPo> { it.createdAt }.thenByDescending { it.id }).map { batch -> row(
                "batchId" to batch.id,
                "batchCode" to batch.batchCode,
                "batchStatus" to batch.batchStatus,
                "reconciliationStatus" to batch.reconciliationStatus,
                "totalRows" to batch.totalRows,
                "insertedRows" to batch.insertedRows,
                "updatedRows" to batch.updatedRows,
                "skippedRows" to batch.skippedRows,
                "failedRows" to batch.failedRows,
                "dataDateFrom" to batch.dataDateFrom,
                "dataDateTo" to batch.dataDateTo,
                "startedAt" to batch.startedAt,
                "completedAt" to batch.completedAt,
            ) },
            "jiraPassRecords" to statuses.entries.sortedByDescending { it.value.sources.size }.map { (status, value) -> row(
                "status" to status,
                "passRecordCount" to value.sources.size.toLong(),
                "closedOrResolvedCount" to value.closed,
            ) },
            "reconciliationMethodology" to listOf(row(
                "batchScope" to "统计周期内创建的Jira文件批次，仅展示非正文安全元数据",
                "recordScope" to "Jira记录统计强制masking_status=PASS",
                "reconciliation" to "总行数与新增、更新、跳过、失败行按导入批次事实展示",
                "numberSource" to "PARAMETERIZED_SQL",
            )),
        )
    }

    private fun weekly(
        scope: DataAccessScope,
        start: LocalDateTime,
        end: LocalDateTime,
        summary: SummaryAccumulator,
    ): LinkedHashMap<String, Any> {
        val priorStart = start.minusWeeks(1)
        val priorEnd = end.minusWeeks(1)
        data class ChannelCount(var current: Long = 0, var previous: Long = 0)
        data class JiraTiming(var totalHours: Long = 0, var hourCount: Long = 0, var closed: Long = 0, var samples: Long = 0)
        val daily = linkedMapOf<Pair<LocalDate, String>, Long>()
        val channels = linkedMapOf<String, ChannelCount>()
        val category = linkedMapOf<Pair<String, String>, Long>()
        val versions = linkedMapOf<Pair<String, String>, Long>()
        val sentiment = linkedMapOf<Pair<LocalDate, String>, Long>()
        val jiraTiming = linkedMapOf<String, JiraTiming>()
        val clusters = linkedMapOf<Long, ClusterAggregate>()
        scan(scope, null, null, ReportSubject(), setOf(FactRelation.CLUSTER, FactRelation.JIRA)) { facts ->
            facts.forEach { fact ->
                summary.accept(fact)
                val comparison = channels.getOrPut(fact.source.channel) { ChannelCount() }
                fact.contents.forEach { content ->
                    val occurredAt = fact.source.occurredAt
                    if (within(occurredAt, start, end)) {
                        val date = occurredAt!!.toLocalDate()
                        increment(daily, date to fact.source.channel)
                        increment(category, (content.issueCategory ?: "OTHER") to (content.severityLevel ?: "UNKNOWN"))
                        increment(versions, (fact.source.packageName ?: "UNKNOWN") to (fact.source.appVersion ?: "UNKNOWN"))
                        increment(sentiment, date to (content.userSentiment ?: "UNKNOWN"))
                        addClusters(clusters, fact, content)
                        fact.jira.forEach { jira ->
                            val timing = jiraTiming.getOrPut(jira.projectKey ?: "UNKNOWN") { JiraTiming() }
                            timing.samples++
                            val sourceTime = fact.source.sourceEventTime
                            if (sourceTime != null && jira.resolvedAt != null) {
                                timing.totalHours += Duration.between(sourceTime, jira.resolvedAt).toHours()
                                timing.hourCount++
                            }
                            if (jira.resolvedAt != null || jira.status.orEmpty().uppercase() in CLOSED_JIRA) timing.closed++
                        }
                        comparison.current++
                    } else if (within(occurredAt, priorStart, priorEnd)) comparison.previous++
                }
            }
        }
        val merged = daily.entries.groupBy({ it.key.first }, { it.value }).mapValues { it.value.sum() }.toSortedMap()
        var previous: Long? = null
        val mergedRows = merged.map { (date, count) ->
            val result = row("date" to date, "count" to count, "dailyChangeRate" to previous?.let { percentageChange(count, it) })
            previous = count
            result
        }
        val totalVersions = versions.values.sum()
        return linkedMapOf(
            "dailyTrend" to daily.entries
                .sortedWith(compareBy({ it.key.first }, { it.key.second }))
                .map { row("date" to it.key.first, "channel" to it.key.second, "count" to it.value) },
            "mergedBus" to mergedRows,
            "channelComparison" to channels.map { (channel, count) ->
                row(
                    "channel" to channel,
                    "count" to count.current,
                    "previousCount" to count.previous,
                    "changeRate" to percentageChange(count.current, count.previous),
                )
            },
            "categorySeverity" to category.entries.sortedByDescending { it.value }.map { row("category" to it.key.first, "severity" to it.key.second, "count" to it.value) },
            "versionTop10" to versions.entries.sortedByDescending { it.value }.take(10).map {
                row(
                    "packageName" to it.key.first,
                    "version" to it.key.second,
                    "count" to it.value,
                    "concentrationRate" to percent(it.value, totalVersions),
                )
            },
            "hotClusters" to clusterRows(clusters, 10),
            "crossChannelClusters" to clusterRows(clusters.filterValues { it.channels.size >= 2 }, 10),
            "jiraTimeliness" to jiraTiming.map { (team, value) -> row(
                "team" to team,
                "avgResolutionHours" to average(value.totalHours, value.hourCount),
                "closureRate" to percent(value.closed, value.samples),
                "sampleCount" to value.samples,
            ) }.sortedWith(compareByDescending<Map<String, Any?>> { it["avgResolutionHours"] as? BigDecimal }),
            "sentimentTrend" to sentiment.entries
                .sortedWith(compareBy({ it.key.first }, { it.key.second }))
                .map { row("date" to it.key.first, "sentiment" to it.key.second, "count" to it.value) },
            "methodologyNotes" to listOf(row(
                "mergedBus" to "各渠道日计数简单求和，仅反映整体变化方向",
                "comparability" to "跨渠道数据不可直接比较绝对量",
                "freshness" to "SDK和旧反馈平台为实时接入；Jira取决于API同步或文件补数，质量平台和红黑榜取决于最近文件导入时间",
            )),
        )
    }

    private fun version(
        scope: DataAccessScope,
        start: LocalDateTime,
        end: LocalDateTime,
        request: GenerateReportRequest,
        summary: SummaryAccumulator,
    ): LinkedHashMap<String, Any> {
        val packageName = request.packageName!!
        val version = request.version!!
        val released = request.releasedAt!!.atStartOfDay()
        val baseline = released.minusDays(7)
        data class ChannelOverview(val sources: MutableSet<Long> = linkedSetOf(), val devices: MutableSet<String> = linkedSetOf())
        val overview = linkedMapOf<String, ChannelOverview>()
        val currentChannels = linkedMapOf<String, Long>()
        val modules = linkedMapOf<String, Long>()
        val devices = linkedMapOf<String, Long>()
        val provinces = linkedMapOf<String, Long>()
        val trend = linkedMapOf<LocalDate, Long>()
        val releaseClusters = linkedMapOf<Long, ClusterAggregate>()
        val baselineClusterIds = linkedSetOf<Long>()
        var releaseCount = 0L
        var baselineCount = 0L
        var releaseSevereCount = 0L
        var baselineSevereCount = 0L
        scan(scope, null, null, ReportSubject(packageName = packageName), setOf(FactRelation.CLUSTER, FactRelation.IDENTITY, FactRelation.SDK)) { facts ->
            facts.forEach { fact ->
                summary.accept(fact)
                val occurredAt = fact.source.occurredAt
                val isVersion = fact.source.appVersion == version
                fact.contents.forEach { content ->
                    if (isVersion) {
                        increment(modules, content.functionModule ?: "UNKNOWN")
                        val sourceDevices = fact.sdk.ifEmpty { listOf(ReportFactSdkPo(sourceId = fact.source.id)) }
                        sourceDevices.forEach { increment(devices, it.deviceModel ?: "UNKNOWN") }
                        increment(provinces, fact.source.provinceCode ?: "UNKNOWN")
                        if (content.severityLevel in SEVERE) releaseSevereCount++
                    }
                    if (isVersion && occurredAt != null && occurredAt >= released) releaseCount++
                    if (occurredAt != null && occurredAt >= baseline && occurredAt < released) {
                        baselineCount++
                        if (content.severityLevel in SEVERE) baselineSevereCount++
                    }
                    if (isVersion && within(occurredAt, start, end)) {
                        increment(currentChannels, fact.source.channel)
                        increment(trend, occurredAt!!.toLocalDate())
                    }
                    if (isVersion && occurredAt != null && occurredAt >= released) addClusters(releaseClusters, fact, content)
                    if (occurredAt != null && occurredAt >= baseline && occurredAt < released) {
                        baselineClusterIds += fact.links.map { it.clusterId }
                    }
                }
                if (isVersion && within(occurredAt, start, end)) {
                    val channel = overview.getOrPut(fact.source.channel) { ChannelOverview() }
                    channel.sources += fact.source.id
                    if (fact.source.channel == "SDK") fact.identities.forEach { channel.devices += fingerprint(it.fingerprint) }
                }
            }
        }
        val newClusters = releaseClusters.filterKeys { it !in baselineClusterIds }.values.map { aggregate -> row(
            "clusterId" to aggregate.id,
            "title" to aggregate.title,
            "severity" to aggregate.severity,
            "recordCount" to aggregate.sourceIds.size.toLong(),
        ) }
        val metadata = repository.latestConfig("APP_VERSION:$packageName:$version")
        return linkedMapOf(
            "releaseMetadata" to if (metadata == null) emptyList<Map<String, Any?>>() else listOf(row("releaseMetadata" to metadata)),
            "versionOverview" to overview.map { (channel, value) ->
                row(
                    "channel" to channel,
                    "count" to value.sources.size.toLong(),
                    "affectedDevices" to value.devices.size.toLong(),
                )
            },
            "newClusters" to newClusters,
            "baselineComparison" to listOf(row(
                "releaseCount" to releaseCount,
                "baselineCount" to baselineCount,
                "releaseSevereCount" to releaseSevereCount,
                "baselineSevereCount" to baselineSevereCount,
            )),
            "channelDistribution" to distribution(currentChannels, "channel"),
            "concentration" to concentration("MODULE", modules) + concentration("DEVICE", devices) + concentration("PROVINCE", provinces),
            "threeDayTrend" to trend.toSortedMap().map { row("date" to it.key, "count" to it.value) },
        )
    }

    private fun quality(
        scope: DataAccessScope,
        start: LocalDateTime,
        end: LocalDateTime,
        summary: SummaryAccumulator,
    ): LinkedHashMap<String, Any> {
        val clusters = linkedMapOf<Long, ClusterAggregate>()
        data class ModuleCounts(var quality: Long = 0, var sdk: Long = 0, var jira: Long = 0)
        val matrix = linkedMapOf<String, ModuleCounts>()
        scan(scope, start, end, ReportSubject(), setOf(FactRelation.CLUSTER)) { facts ->
            facts.forEach { fact ->
                summary.accept(fact)
                fact.contents.forEach { content ->
                    addClusters(clusters, fact, content)
                    fact.links.forEach {
                        val count = matrix.getOrPut(content.functionModule ?: "UNKNOWN") { ModuleCounts() }
                        when (fact.source.channel) {
                            "QUALITY" -> count.quality++
                            "SDK" -> count.sdk++
                            "JIRA" -> count.jira++
                        }
                    }
                }
            }
        }
        val qualityClusters = clusters.values.filter { it.hasQuality }
        val linked = qualityClusters.count { it.hasSdk || it.hasJira }.toLong()
        return linkedMapOf(
            "linkageOverview" to listOf(row(
                "qualityClusters" to qualityClusters.size.toLong(),
                "linkedClusters" to linked.takeIf { qualityClusters.isNotEmpty() },
                "linkedRate" to percent(linked, qualityClusters.size.toLong()),
            )),
            "linkageMatrix" to matrix.map { (module, count) -> row("module" to module, "qualityCount" to count.quality, "sdkCount" to count.sdk, "jiraCount" to count.jira) },
            "qualityOnly" to exclusiveRows(clusters) { it.hasQuality && !it.hasSdk && !it.hasJira },
            "reverseBlindSpots" to exclusiveRows(clusters) { !it.hasQuality && (it.hasSdk || it.hasJira) },
            "firstSeenLag" to clusters.values.filter { it.qualityFirstSeen != null && it.userFirstSeen != null }.map { aggregate -> row(
                "clusterId" to aggregate.id,
                "qualityFirstSeen" to aggregate.qualityFirstSeen,
                "userFirstSeen" to aggregate.userFirstSeen,
                "lagHours" to Duration.between(aggregate.qualityFirstSeen, aggregate.userFirstSeen).toHours(),
            ) },
        )
    }

    private fun redblack(
        scope: DataAccessScope,
        start: LocalDateTime,
        end: LocalDateTime,
        summary: SummaryAccumulator,
    ): LinkedHashMap<String, Any> {
        val overview = linkedMapOf<Pair<String, String>, Long>()
        val positive = linkedMapOf<String, Long>()
        val bursts = linkedMapOf<Pair<Long, LocalDateTime>, Long>()
        val clusters = linkedMapOf<Long, ClusterAggregate>()
        scan(scope, start, end, ReportSubject(), setOf(FactRelation.CLUSTER, FactRelation.REDBLACK, FactRelation.AI)) { facts ->
            facts.forEach { fact ->
                summary.accept(fact)
                fact.contents.forEach { content ->
                    addClusters(clusters, fact, content)
                    fact.redblack.forEach { redblack ->
                        increment(overview, (redblack.listType ?: "UNKNOWN") to (content.userSentiment ?: "UNKNOWN"))
                        if (redblack.listType == "RED" && content.userSentiment == "POSITIVE") increment(positive, content.functionModule ?: "UNKNOWN")
                    }
                    if (fact.source.channel == "REDBLACK") fact.ai.forEach { ai ->
                        ai.textFingerprint?.let { increment(bursts, it to fact.source.occurredAt!!.withMinute(0).withSecond(0).withNano(0)) }
                    }
                }
            }
        }
        return linkedMapOf(
            "redblackOverview" to overview.map { row("listType" to it.key.first, "sentiment" to it.key.second, "count" to it.value) },
            "blackTop10" to clusterRows(clusters.filterValues { it.hasBlack }, 10),
            "resonance" to clusterRows(clusters.filterValues { it.hasBlack && (it.hasSdk || it.hasJira) }, 20),
            "positiveModules" to positive.entries.sortedByDescending { it.value }.map { row("module" to it.key, "count" to it.value) },
            "channelMatrix" to clusters.values.filter { it.hasRedblack }.map { aggregate -> row(
                "clusterId" to aggregate.id,
                "redblack" to aggregate.hasRedblack,
                "sdk" to aggregate.hasSdk,
                "jira" to aggregate.hasJira,
                "exclusiveSignal" to (aggregate.hasRedblack && !aggregate.hasSdk && !aggregate.hasJira),
            ) },
            "burstCandidates" to bursts.entries.filter { it.value > 10 }.sortedByDescending { it.value }.map { row(
                "fingerprint" to it.key.first,
                "hourWindow" to it.key.second.format(HOUR_FORMAT),
                "count" to it.value,
            ) },
        )
    }

    private fun risk(
        scope: DataAccessScope,
        start: LocalDateTime,
        end: LocalDateTime,
        summary: SummaryAccumulator,
    ): LinkedHashMap<String, Any> {
        val previousStart = start.minusDays(1)
        val previousEnd = start.minusNanos(1)
        data class Sentiment(var current: Long = 0, var previous: Long = 0)
        val sentiment = linkedMapOf<Pair<String, String>, Sentiment>()
        val clusters = linkedMapOf<Long, ClusterAggregate>()
        scan(scope, null, null, ReportSubject(), setOf(FactRelation.CLUSTER, FactRelation.REDBLACK)) { facts ->
            facts.forEach { fact ->
                summary.accept(fact)
                fact.contents.forEach { content ->
                    val key = fact.source.channel to (content.functionModule ?: "UNKNOWN")
                    val aggregate = sentiment.getOrPut(key) { Sentiment() }
                    if (content.userSentiment == "NEGATIVE") {
                        if (within(fact.source.occurredAt, start, end)) aggregate.current++
                        if (within(fact.source.occurredAt, previousStart, previousEnd)) aggregate.previous++
                    }
                    if (within(fact.source.occurredAt, start, end)) addClusters(clusters, fact, content)
                }
            }
        }
        val alerts = activeAlerts()
        val surges = sentiment.mapNotNull { (key, value) ->
            val change = percentageChange(value.current, value.previous)
            if (change == null || change <= BigDecimal("30")) null else row(
                "channel" to key.first,
                "module" to key.second,
                "negativeCount" to value.current,
                "previousNegativeCount" to value.previous,
                "changeRate" to change,
            )
        }
        val resonance = clusterRows(clusters.filterValues { it.hasBlack && it.channels.size >= 2 }, 20)
        val severe = clusters.values.filter { it.severeChannels.size >= 2 }.map { aggregate -> row(
            "clusterId" to aggregate.id,
            "title" to aggregate.title,
            "severity" to aggregate.severeSeverity,
            "channelCount" to aggregate.severeChannels.size.toLong(),
            "channels" to aggregate.severeChannels.sorted().joinToString(","),
        ) }
        val alertScore = alerts.sumOf { if (it["level"] == "P0") 10 else 4 }
        val score = alertScore + surges.size * 3 + resonance.size * 4 + severe.size * 5
        return linkedMapOf(
            "riskLevel" to listOf(row(
                "score" to score,
                "level" to when {
                    score >= 20 -> "CRITICAL"
                    score >= 10 -> "HIGH"
                    score >= 4 -> "MEDIUM"
                    else -> "LOW"
                },
                "factors" to mapOf(
                    "activeAlertScore" to alertScore,
                    "negativeSurges" to surges.size,
                    "blackResonance" to resonance.size,
                    "severeCrossChannel" to severe.size,
                ),
            )),
            "activeAlerts" to alerts,
            "negativeSentimentSurges" to surges,
            "crossChannelRisks" to severe,
            "blackResonance" to resonance,
            "hotProblems" to clusterRows(clusters, 5),
            "attentionItems" to (alerts + surges + resonance + severe).take(3),
        )
    }

    private fun scan(
        scope: DataAccessScope,
        from: LocalDateTime?,
        to: LocalDateTime?,
        sourceFilter: ReportSubject,
        relations: Set<FactRelation>,
        consumer: (List<ReportSourceFact>) -> Unit,
    ) {
        var cursor = 0L
        while (true) {
            val sources = repository.sourcePage(
                ReportFactSourceQuery(
                    from = from,
                    to = to,
                    channels = scope.channels.ifEmpty { setOf("__NONE__") },
                    regions = scope.regions?.ifEmpty { setOf("__NONE__") },
                    packageName = sourceFilter.packageName,
                    appVersion = sourceFilter.appVersion,
                    afterId = cursor,
                    limit = PAGE_SIZE,
                ),
            )
            if (sources.isEmpty()) break
            val contentBySource = repository.contents(sources.map { it.id })
                .filter { !it.deleted && it.maskingStatus == "PASS" && allowedModule(it.functionModule, scope) }
                .groupBy { it.sourceId }
            val eligibleSources = sources.filter { it.id in contentBySource && sourceFilter.matches(it) }
            val ids = eligibleSources.map { it.id }
            if (ids.isNotEmpty()) {
                val linksBySource = repository.links(ids).groupBy { it.sourceId }
                val clusterIds = if (FactRelation.CLUSTER in relations) linksBySource.values.flatten().map { it.clusterId }.distinct() else emptyList()
                val clusterTitles = if (clusterIds.isEmpty()) emptyMap() else repository.clusters(clusterIds).associate { it.id to it.title }
                val identity = if (FactRelation.IDENTITY in relations) repository.identities(ids).groupBy { it.sourceId } else emptyMap()
                val sdk = if (FactRelation.SDK in relations) repository.sdk(ids).groupBy { it.sourceId } else emptyMap()
                val jira = if (FactRelation.JIRA in relations) repository.jira(ids).groupBy { it.sourceId } else emptyMap()
                val redblack = if (FactRelation.REDBLACK in relations) repository.redblack(ids).groupBy { it.sourceId } else emptyMap()
                val ai = if (FactRelation.AI in relations) repository.ai(ids).groupBy { it.sourceId } else emptyMap()
                consumer(eligibleSources.map { source ->
                    ReportSourceFact(
                        source,
                        contentBySource.getValue(source.id),
                        linksBySource[source.id].orEmpty(),
                        clusterTitles,
                        identity[source.id].orEmpty(),
                        sdk[source.id].orEmpty(),
                        jira[source.id].orEmpty(),
                        redblack[source.id].orEmpty(),
                        ai[source.id].orEmpty(),
                    )
                })
            }
            cursor = sources.last().id
            if (sources.size < PAGE_SIZE) break
        }
    }

    private fun addClusters(
        aggregates: MutableMap<Long, ClusterAggregate>,
        fact: ReportSourceFact,
        content: ReportFactContentPo,
    ) {
        fact.links.forEach { link ->
            val aggregate = aggregates.getOrPut(link.clusterId) { ClusterAggregate(link.clusterId) }
            aggregate.title = fact.clusterTitles[link.clusterId] ?: aggregate.title
            aggregate.sourceIds += fact.source.id
            aggregate.channels += fact.source.channel
            if (content.severityLevel != null && (aggregate.severity == null || content.severityLevel!! > aggregate.severity!!)) {
                aggregate.severity = content.severityLevel
            }
            if (content.severityLevel in SEVERE &&
                (aggregate.severeSeverity == null || content.severityLevel!! > aggregate.severeSeverity!!)
            ) {
                aggregate.severeSeverity = content.severityLevel
            }
            if (content.severityLevel in SEVERE) aggregate.severeChannels += fact.source.channel
            val occurredAt = fact.source.occurredAt
            when (fact.source.channel) {
                "QUALITY" -> {
                    aggregate.hasQuality = true
                    if (occurredAt != null && (aggregate.qualityFirstSeen == null || occurredAt < aggregate.qualityFirstSeen)) aggregate.qualityFirstSeen = occurredAt
                }
                "SDK" -> {
                    aggregate.hasSdk = true
                    if (occurredAt != null && (aggregate.userFirstSeen == null || occurredAt < aggregate.userFirstSeen)) aggregate.userFirstSeen = occurredAt
                }
                "JIRA" -> {
                    aggregate.hasJira = true
                    if (occurredAt != null && (aggregate.userFirstSeen == null || occurredAt < aggregate.userFirstSeen)) aggregate.userFirstSeen = occurredAt
                }
                "REDBLACK" -> aggregate.hasRedblack = true
            }
            if (fact.source.channel == "REDBLACK" && fact.redblack.any { it.listType == "BLACK" }) aggregate.hasBlack = true
        }
    }

    private fun clusterRows(aggregates: Map<Long, ClusterAggregate>, limit: Int): List<Map<String, Any?>> =
        aggregates.values.sortedWith(compareByDescending<ClusterAggregate> { it.sourceIds.size }.thenBy { it.id }).take(limit).map { aggregate -> row(
            "clusterId" to aggregate.id,
            "title" to aggregate.title,
            "recordCount" to aggregate.sourceIds.size.toLong(),
            "channelCount" to aggregate.channels.size.toLong(),
            "channels" to aggregate.channels.sorted().joinToString(","),
        ) }

    private fun exclusiveRows(
        aggregates: Map<Long, ClusterAggregate>,
        predicate: (ClusterAggregate) -> Boolean,
    ): List<Map<String, Any?>> = aggregates.values.filter(predicate).take(20).map { aggregate -> row(
        "clusterId" to aggregate.id,
        "title" to aggregate.title,
        "channels" to aggregate.channels.sorted().joinToString(","),
    ) }

    private fun activeAlerts(): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        var cursor = 0L
        while (true) {
            val alerts = repository.activeAlerts(cursor, PAGE_SIZE)
            if (alerts.isEmpty()) break
            val rules = repository.alertRules(alerts.map { it.ruleId }.distinct()).associateBy { it.id }
            alerts.forEach { alert -> rules[alert.ruleId]?.let { rule -> result += row("alertId" to alert.id, "level" to alert.level, "title" to rule.title) } }
            cursor = alerts.last().id
            if (alerts.size < PAGE_SIZE) break
        }
        return result
    }

    private fun importBatches(from: LocalDateTime?, to: LocalDateTime?, channel: String?): List<ReportFactImportBatchPo> {
        val result = mutableListOf<ReportFactImportBatchPo>()
        var cursor = 0L
        while (true) {
            val page = repository.importBatchPage(from, to, channel, cursor, PAGE_SIZE)
            result += page
            if (page.size < PAGE_SIZE) break
            cursor = page.last().id
        }
        return result
    }

    /** 只跨页保留累计值，避免为了一个失败行计数加载全部历史批次。 */
    private fun failedImportCount(to: LocalDateTime): Long {
        var total = 0L
        var cursor = 0L
        while (true) {
            val page = repository.importBatchPage(null, to, null, cursor, PAGE_SIZE)
            if (page.isEmpty()) break
            total += repository.failedImportCounts(page.map { it.id }).sumOf { it.count }
            cursor = page.last().id
            if (page.size < PAGE_SIZE) break
        }
        return total
    }

    private fun concentration(dimension: String, counts: Map<String, Long>): List<Map<String, Any?>> {
        val total = counts.values.sum()
        return counts.map { (value, count) -> row(
            "dimension" to dimension,
            "value" to value,
            "count" to count,
            "percentage" to percent(count, total),
            "warning" to (count * 2 > total),
        ) }
    }

    private fun distribution(counts: Map<String, Long>, key: String): List<Map<String, Any?>> {
        val total = counts.values.sum()
        return counts.map { (value, count) -> row(key to value, "count" to count, "percentage" to percent(count, total)) }
    }

    private fun allowedModule(module: String?, scope: DataAccessScope): Boolean =
        scope.modules?.contains((module ?: "UNKNOWN").uppercase()) != false

    private fun mappingSuccess(value: String?): Boolean = value?.toDoubleOrNull()?.let { it == 0.0 } ?: true

    private fun within(value: LocalDateTime?, start: LocalDateTime, end: LocalDateTime): Boolean =
        value != null && value >= start && value <= end

    private fun percent(numerator: Long, denominator: Long): BigDecimal? =
        if (denominator == 0L) null else BigDecimal.valueOf(numerator).multiply(HUNDRED)
            .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP)

    private fun percentageChange(current: Long, previous: Long): BigDecimal? =
        if (previous == 0L) null else BigDecimal.valueOf(current - previous).multiply(HUNDRED)
            .divide(BigDecimal.valueOf(previous), 2, RoundingMode.HALF_UP)

    private fun average(total: Long, count: Long): BigDecimal? =
        if (count == 0L) null else BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP)

    private fun fingerprint(value: ByteArray): String = value.joinToString("") { "%02x".format(it) }

    private fun <K> increment(values: MutableMap<K, Long>, key: K, amount: Long = 1) {
        values[key] = values.getOrDefault(key, 0) + amount
    }

    private fun row(vararg values: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*values)

    private fun drill(
        row: Map<*, *>,
        start: LocalDate,
        end: LocalDate,
        scope: DataAccessScope,
    ): Map<String, Any?> {
        val cluster = (row["clusterId"] as? Number)?.toLong()
        val alert = (row["alertId"] as? Number)?.toLong()
        val query = linkedMapOf<String, Any?>(
            "from" to (row["date"]?.toString() ?: start.toString()),
            "to" to (row["date"]?.toString() ?: end.toString()),
        )
        row["channel"]?.let { query["channels"] = it.toString().lowercase() }
        row["module"]?.let { query["modules"] = it }
        row["severity"]?.let { query["severities"] = it }
        row["version"]?.let { query["appVersion"] = it }
        if (row["channel"] == null && scope.channels.size < 4) {
            query["channels"] = scope.channels.sorted().joinToString(",").lowercase()
        }
        scope.modules?.let { if (row["module"] == null) query["modules"] = it.sorted().joinToString(",") }
        scope.regions?.let { query["regions"] = it.sorted().joinToString(",") }
        return mapOf(
            "path" to when {
                cluster != null -> "/report/cluster/$cluster"
                alert != null -> "/alert/center"
                else -> "/channel/all"
            },
            "query" to query,
            "scopeBound" to true,
        )
    }

    companion object {
        private const val PAGE_SIZE = 500
        private val HUNDRED = BigDecimal("100")
        private val CLOSED_JIRA = setOf("CLOSED", "DONE", "RESOLVED")
        private val SEVERE = setOf("S0", "S1")
        private val HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00")
    }
}
