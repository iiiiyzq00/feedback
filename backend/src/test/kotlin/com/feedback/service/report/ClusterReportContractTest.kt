package com.feedback.service.report

import com.feedback.config.ProblemAnalysisReadProperties
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionGenerationPo
import com.feedback.model.query.problem.analysis.AnalysisCriteria
import com.feedback.application.port.problem.analysis.ClusterDetailSnapshot
import com.feedback.application.port.problem.analysis.ClusterFeedbackItemSnapshot
import com.feedback.application.port.problem.analysis.ClusterFeedbackSnapshot
import com.feedback.application.port.problem.analysis.ClusterReadPort
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import com.feedback.service.query.analysis.ClusterDetailQuery
import com.feedback.service.query.analysis.ClusterFeedbackQuery
import com.feedback.service.query.analysis.ClusterDetailResponse
import com.feedback.service.query.analysis.ClusterFeedbackItemResponse
import com.feedback.service.query.analysis.ClusterReadApplicationService
import com.feedback.service.query.analysis.ProblemAnalysisPage
import com.feedback.service.query.analysis.ProblemAnalysisPeriod
import com.feedback.service.query.listprojection.ActiveGenerationProvider
import com.feedback.service.query.listprojection.ProjectionCapability
import io.mockk.every
import io.mockk.mockk
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** 使用应用服务行为、公开 DTO 和 BoundSql 冻结反馈问题簇读取合同，不依赖实现源码文本。 */
class ClusterReportContractTest {
    private val properties = ProblemAnalysisReadProperties().apply { readEnabled = true }
    private val generations = mockk<ActiveGenerationProvider>()
    private val reads = mockk<ClusterReadPort>()
    private val permissions = mockk<DataPermissionService>()
    private val principal = mockk<CurrentWebPrincipalProvider>()
    private val service = ClusterReadApplicationService(
        properties, generations, reads, permissions, principal
    )

    init {
        every { principal.require() } returns WebPrincipal(
            9, "phase", "", "Phase", setOf("PHASE_ONE_PRODUCT"),
            setOf("access:phase_one_workspace"), authProvider = "SSO"
        )
        every { permissions.scope() } returns DataAccessScope(setOf("SDK", "STATION_FEEDBACK"), null, null)
        every { generations.require(ProjectionCapability.PROBLEM_ANALYSIS) } returns
            FeedbackListProjectionGenerationPo(id = 7)
    }

    @Test
    fun `cluster detail preserves zero device fallback and has no severity presentation fields`() {
        every { reads.detail(any()) } returns detail(deviceCount = 0)

        val response = service.detail(ClusterDetailQuery(42, ProblemAnalysisPeriod(null, null)))
        val fields = ClusterDetailResponse::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }

        assertEquals(0, response.uniqueDevices)
        assertEquals(1, response.channelCount)
        setOf("highestSeverity", "severityS0Count", "severityUnknownCount", "criticalCount", "normalCount")
            .forEach { assertFalse(fields.contains(it), it) }
    }

    @Test
    fun `feedback response maps projected time controlled information and evidence counts`() {
        val reportedAt = LocalDateTime.of(2026, 8, 31, 18, 20)
        every { reads.feedback(any()) } returns ClusterFeedbackSnapshot(
            1,
            listOf(ClusterFeedbackItemSnapshot(
                sourceId = 10001,
                sourceRecordId = "SDK-1",
                title = "标题",
                description = "安全正文",
                sourceChannel = "SDK",
                reportedAt = reportedAt,
                controlledInfoTypes = listOf("DEVICE_SN", "APPLICATION_LOG"),
                attachmentCount = 4,
                screenshotCount = 3,
                logCount = 2,
                videoCount = 1,
                decisionType = null,
                matchSimilarity = null,
                decisionReason = null,
                aiModel = null,
                aiConfidence = null,
                aiAction = null,
                aiExplanation = null
            ))
        )

        val item = service.feedback(ClusterFeedbackQuery(
            42, ProblemAnalysisPage(1, 20), ProblemAnalysisPeriod(null, null)
        )).items.single()
        val fields = ClusterFeedbackItemResponse::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }

        assertEquals(reportedAt, item.reportedAt)
        assertEquals("DEVICE_SN,APPLICATION_LOG", item.controlledInfoTypes)
        assertEquals(listOf(4, 3, 2, 1),
            listOf(item.attachmentCount, item.screenshotCount, item.logCount, item.videoCount))
        assertTrue(fields.containsAll(setOf(
            "controlledInfoTypes", "attachmentCount", "screenshotCount", "logCount", "videoCount", "reportedAt"
        )))
    }

    @Test
    fun `identity and logical channel SQL retain governed counting semantics`() {
        val configuration = configuration(
            "mybatis/mapper/problem/analysis/ProblemAnalysisIdentityMapper.xml",
            "mybatis/mapper/problem/analysis/ProblemAnalysisMemberMapper.xml"
        )
        val parameters = mapOf(
            "generationId" to 7L,
            "criteria" to AnalysisCriteria(
                listOf("SDK", "STATION_FEEDBACK"), null, null, null, null
            ),
            "clusterIds" to listOf(42L),
            "clusterId" to 42L
        )
        val identitySql = sql(configuration,
            "com.feedback.mapper.problem.analysis.ProblemAnalysisIdentityMapper.counts", parameters)
        val summarySql = sql(configuration,
            "com.feedback.mapper.problem.analysis.ProblemAnalysisMemberMapper.findClusterSummary", parameters)

        assertTrue(identitySql.contains("COUNT(DISTINCT CASE WHEN identity_type='DEVICE_SN'"), identitySql)
        assertFalse(identitySql.contains("NULLIF(COUNT(DISTINCT CASE WHEN identity_scope='DEVICE'"), identitySql)
        assertTrue(summarySql.contains("COUNT(DISTINCT logical_channel) logical_channel_count"), summarySql)
        assertTrue(summarySql.contains("SUM(logical_channel='LEARNING_MACHINE')"), summarySql)
        assertTrue(summarySql.contains("reported_at"), summarySql)
    }

    private fun configuration(vararg resources: String) = Configuration().apply {
        isMapUnderscoreToCamelCase = true
        resources.forEach { resource ->
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, this, resource, sqlFragments).parse()
            }
        }
    }

    private fun sql(configuration: Configuration, statement: String, parameters: Map<String, Any?>): String =
        configuration.getMappedStatement(statement).getBoundSql(parameters).sql
            .replace(Regex("\\s+"), " ").trim()

    private fun detail(deviceCount: Long) = ClusterDetailSnapshot(
        id = 42,
        name = "课程卡顿",
        canonicalSymptom = "课程播放卡顿",
        representativeDescription = null,
        representativeTitle = null,
        functionModule = "课堂",
        issueCategory = "性能",
        status = "ACTIVE",
        lifecycleVersion = 1,
        mergedIntoClusterId = null,
        splitFromClusterId = null,
        statusReason = null,
        recordCount = 2,
        logicalChannelCount = 1,
        lastOccurredAt = null,
        firstOccurredAt = null,
        userCount = null,
        deviceCount = deviceCount,
        stationCount = 2,
        jiraCount = 0,
        qualityCount = 0,
        redblackCount = 0
    )
}
