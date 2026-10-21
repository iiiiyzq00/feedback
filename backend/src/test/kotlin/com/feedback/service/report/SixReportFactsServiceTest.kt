package com.feedback.service.report

import com.feedback.repository.report.SixReportFactsRepository
import com.feedback.model.po.report.ReportFactClusterLinkPo
import com.feedback.model.po.report.ReportFactClusterPo
import com.feedback.model.po.report.ReportFactContentPo
import com.feedback.model.po.report.ReportFactIdentityPo
import com.feedback.model.po.report.ReportFactRedblackPo
import com.feedback.model.po.report.ReportFactSourcePo
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SixReportFactsServiceTest {
    private val repository = mockk<SixReportFactsRepository>(relaxed = true)
    private val permissions = mockk<DataPermissionService>()
    private val service = SixReportFactsService(repository, permissions)

    @Test
    fun `all seven report types retain their complete section contract when facts are empty`() {
        every { permissions.scope() } returns DataAccessScope(
            setOf("SDK", "STATION_FEEDBACK", "JIRA", "QUALITY", "REDBLACK"),
            null,
            null,
        )
        every { repository.sourcePage(any()) } returns emptyList()
        every { repository.importBatchPage(any(), any(), any(), any(), any()) } returns emptyList()
        every { repository.pendingAiReviews(any()) } returns 0
        every { repository.activeAlerts(any(), any()) } returns emptyList()
        val expected = mapOf(
            "DAILY_INGEST" to setOf("ingestOverview", "importBatchStatus", "maskingStatus", "dataQuality", "pendingTasks"),
            "JIRA_IMPORT_RECONCILIATION" to setOf("jiraImportBatches", "jiraPassRecords", "reconciliationMethodology"),
            "WEEKLY_TREND" to setOf("dailyTrend", "mergedBus", "channelComparison", "categorySeverity", "versionTop10", "hotClusters", "crossChannelClusters", "jiraTimeliness", "sentimentTrend", "methodologyNotes"),
            "VERSION_RELEASE" to setOf("releaseMetadata", "versionOverview", "newClusters", "baselineComparison", "channelDistribution", "concentration", "threeDayTrend"),
            "QUALITY_LINKAGE" to setOf("linkageOverview", "linkageMatrix", "qualityOnly", "reverseBlindSpots", "firstSeenLag"),
            "REDBLACK_ANALYSIS" to setOf("redblackOverview", "blackTop10", "resonance", "positiveModules", "channelMatrix", "burstCandidates"),
            "RISK_BRIEF" to setOf("riskLevel", "activeAlerts", "negativeSentimentSurges", "crossChannelRisks", "blackResonance", "hotProblems", "attentionItems"),
        )
        val start = LocalDate.of(2026, 8, 1)
        val end = LocalDate.of(2026, 8, 7)

        expected.forEach { (type, sectionKeys) ->
            val request = GenerateReportRequest(
                type = type,
                packageName = if (type == "VERSION_RELEASE") "com.example.app" else null,
                version = if (type == "VERSION_RELEASE") "2.0.0" else null,
                releasedAt = if (type == "VERSION_RELEASE") start else null,
            )
            val result = service.collect(type, start, end, request)
            @Suppress("UNCHECKED_CAST")
            val sections = result.content.getValue("sections") as Map<String, Any>
            assertEquals(sectionKeys, sections.keys, type)
            assertEquals(0, result.raw, type)
            assertEquals(0, result.unique, type)
        }
    }

    @Test
    fun `weekly trend assembles safe source content and main cluster without database joins`() {
        allowAll()
        val occurred = LocalDateTime.of(2026, 8, 2, 10, 0)
        every { repository.sourcePage(any()) } returns listOf(source(1, "SDK", occurred, "com.example", "1.0"))
        every { repository.contents(listOf(1)) } returns listOf(content(1, module = "PLAYER", severity = "S1", sentiment = "NEGATIVE"))
        every { repository.links(listOf(1)) } returns listOf(ReportFactClusterLinkPo(1, 9, "MAIN"))
        every { repository.clusters(listOf(9)) } returns listOf(ReportFactClusterPo(9, "播放卡顿"))
        every { repository.jira(listOf(1)) } returns emptyList()

        val result = service.collect(
            "WEEKLY_TREND",
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 7),
            GenerateReportRequest("WEEKLY_TREND"),
        )

        assertEquals(1, result.raw)
        assertEquals(1, result.unique)
        val sections = sections(result)
        assertEquals(1L, rows(sections, "dailyTrend").single()["count"])
        assertEquals("播放卡顿", rows(sections, "hotClusters").single()["title"])
    }

    @Test
    fun `quality linkage counts quality summary while retaining cross-channel cluster evidence`() {
        allowAll()
        val occurred = LocalDateTime.of(2026, 8, 3, 9, 0)
        every { repository.sourcePage(any()) } returns listOf(
            source(1, "QUALITY", occurred),
            source(2, "SDK", occurred),
        )
        every { repository.contents(listOf(1, 2)) } returns listOf(content(1), content(2))
        every { repository.links(listOf(1, 2)) } returns listOf(
            ReportFactClusterLinkPo(1, 7, "MAIN"),
            ReportFactClusterLinkPo(2, 7, "MAIN"),
        )
        every { repository.clusters(listOf(7)) } returns listOf(ReportFactClusterPo(7, "启动失败"))

        val result = service.collect(
            "QUALITY_LINKAGE",
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 7),
            GenerateReportRequest("QUALITY_LINKAGE"),
        )

        assertEquals(1, result.raw)
        assertEquals(1, result.unique)
        val overview = rows(sections(result), "linkageOverview").single()
        assertEquals(1L, overview["qualityClusters"])
        assertEquals(1L, overview["linkedClusters"])
    }

    @Test
    fun `version report excludes a cluster already present in the seven day baseline`() {
        allowAll()
        val baselineTime = LocalDateTime.of(2026, 7, 29, 12, 0)
        val releaseTime = LocalDateTime.of(2026, 8, 2, 12, 0)
        every { repository.sourcePage(any()) } returns listOf(
            source(1, "SDK", baselineTime, "com.example", "1.0"),
            source(2, "SDK", releaseTime, "com.example", "2.0"),
        )
        every { repository.contents(listOf(1, 2)) } returns listOf(content(1, severity = "S2"), content(2, severity = "S1"))
        every { repository.links(listOf(1, 2)) } returns listOf(
            ReportFactClusterLinkPo(1, 5, "MAIN"),
            ReportFactClusterLinkPo(2, 5, "MAIN"),
        )
        every { repository.clusters(listOf(5)) } returns listOf(ReportFactClusterPo(5, "升级后闪退"))
        every { repository.identities(listOf(1, 2)) } returns listOf(ReportFactIdentityPo(2, byteArrayOf(1, 2, 3)))
        every { repository.sdk(listOf(1, 2)) } returns emptyList()
        every { repository.latestConfig(any()) } returns null

        val result = service.collect(
            "VERSION_RELEASE",
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 3),
            GenerateReportRequest(
                type = "VERSION_RELEASE",
                packageName = "com.example",
                version = "2.0",
                releasedAt = LocalDate.of(2026, 8, 1),
            ),
        )

        assertEquals(1, result.raw)
        assertEquals(1, result.unique)
        assertEquals(emptyList<Map<String, Any?>>(), rows(sections(result), "newClusters"))
        val comparison = rows(sections(result), "baselineComparison").single()
        assertEquals(1L, comparison["releaseCount"])
        assertEquals(1L, comparison["baselineCount"])
    }

    @Test
    fun `risk cross-channel severity only counts channels carrying severe facts`() {
        allowAll()
        val occurred = LocalDateTime.of(2026, 8, 2, 12, 0)
        every { repository.sourcePage(any()) } returns listOf(
            source(1, "REDBLACK", occurred),
            source(2, "SDK", occurred),
        )
        every { repository.contents(listOf(1, 2)) } returns listOf(
            content(1, severity = "S1"),
            content(2, severity = "S2"),
        )
        every { repository.links(listOf(1, 2)) } returns listOf(
            ReportFactClusterLinkPo(1, 8, "MAIN"),
            ReportFactClusterLinkPo(2, 8, "MAIN"),
        )
        every { repository.clusters(listOf(8)) } returns listOf(ReportFactClusterPo(8, "舆情共振"))
        every { repository.redblack(listOf(1, 2)) } returns listOf(ReportFactRedblackPo(1, "BLACK"))
        every { repository.activeAlerts(any(), any()) } returns emptyList()

        val result = service.collect(
            "RISK_BRIEF",
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 3),
            GenerateReportRequest("RISK_BRIEF"),
        )

        val sections = sections(result)
        assertEquals(0, rows(sections, "crossChannelRisks").size)
        assertEquals(1, rows(sections, "blackResonance").size)
    }

    private fun allowAll() {
        every { permissions.scope() } returns DataAccessScope(
            setOf("SDK", "STATION_FEEDBACK", "JIRA", "QUALITY", "REDBLACK"),
            null,
            null,
        )
    }

    private fun source(
        id: Long,
        channel: String,
        occurredAt: LocalDateTime,
        packageName: String? = null,
        version: String? = null,
    ) = ReportFactSourcePo(
        id = id,
        channel = channel,
        packageName = packageName,
        appVersion = version,
        sourceEventTime = occurredAt,
        occurredAt = occurredAt,
    )

    private fun content(
        sourceId: Long,
        module: String = "PLAYER",
        severity: String = "S2",
        sentiment: String = "NEUTRAL",
    ) = ReportFactContentPo(
        sourceId = sourceId,
        title = "标题",
        description = "正文",
        functionModule = module,
        issueCategory = "BUG",
        severityLevel = severity,
        userSentiment = sentiment,
        maskingStatus = "PASS",
    )

    @Suppress("UNCHECKED_CAST")
    private fun sections(result: ReportStats) = result.content.getValue("sections") as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun rows(sections: Map<String, Any>, key: String) = sections.getValue(key) as List<Map<String, Any?>>
}
