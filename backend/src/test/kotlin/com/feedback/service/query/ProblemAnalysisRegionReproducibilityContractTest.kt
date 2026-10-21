package com.feedback.service.query

import com.feedback.config.ProblemAnalysisReadProperties
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionGenerationPo
import com.feedback.model.query.problem.analysis.AnalysisCriteria
import com.feedback.application.port.problem.analysis.ProblemAnalysisReadPort
import com.feedback.application.port.problem.analysis.ProblemAnalysisSnapshot
import com.feedback.application.port.problem.analysis.ProblemAnalysisTotalsSnapshot
import com.feedback.application.port.problem.analysis.ProblemClusterDimensionSnapshot
import com.feedback.application.port.problem.analysis.ProblemClusterSnapshot
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import com.feedback.service.query.analysis.ProblemAnalysisApplicationService
import com.feedback.service.query.analysis.ProblemAnalysisPage
import com.feedback.service.query.analysis.ProblemAnalysisPeriod
import com.feedback.service.query.analysis.ProblemAnalysisQuery
import com.feedback.service.query.analysis.ProblemClusterItemResponse
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

/** 通过公开响应行为与真实 MyBatis 动态 SQL 冻结地域维度，并确认已删除面板不会回到合同。 */
class ProblemAnalysisRegionReproducibilityContractTest {
    @Test
    fun `region response keeps only the first five ranked facts`() {
        val properties = ProblemAnalysisReadProperties().apply { readEnabled = true }
        val generations = mockk<ActiveGenerationProvider>()
        val reads = mockk<ProblemAnalysisReadPort>()
        val permissions = mockk<DataPermissionService>()
        val principal = mockk<CurrentWebPrincipalProvider>()
        every { principal.require() } returns WebPrincipal(
            9, "phase", "", "Phase", setOf("PHASE_ONE_PRODUCT"),
            setOf("access:phase_one_workspace"), authProvider = "SSO"
        )
        every { permissions.scope() } returns DataAccessScope(setOf("SDK"), null, null)
        every { generations.require(ProjectionCapability.PROBLEM_ANALYSIS) } returns
            FeedbackListProjectionGenerationPo(id = 7)
        val rankedRegions = (1..6).map { ProblemClusterDimensionSnapshot("区域$it", 7L - it) }
        every { reads.read(any()) } returns ProblemAnalysisSnapshot(
            ProblemAnalysisTotalsSnapshot(1, 0),
            listOf(cluster(regions = rankedRegions)),
            emptyList()
        )
        val service = ProblemAnalysisApplicationService(
            properties, generations, reads, permissions, principal
        )

        val response = service.analyze(ProblemAnalysisQuery(
            ProblemAnalysisPeriod(null, null), ProblemAnalysisPage(1, 20), ProblemAnalysisPage(1, 20)
        ))

        assertEquals(listOf("区域1", "区域2", "区域3", "区域4", "区域5"),
            response.allClusters.items.single().regions.map { it.region })
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L),
            response.allClusters.items.single().regions.map { it.count })
    }

    @Test
    fun `region SQL groups and ranks the safe projection dimension`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        val resource = "mybatis/mapper/problem/analysis/ProblemAnalysisMemberMapper.xml"
        Resources.getResourceAsReader(resource).use { reader ->
            XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
        }
        val sql = configuration.getMappedStatement(
            "com.feedback.mapper.problem.analysis.ProblemAnalysisMemberMapper.findRegionDimensions"
        ).getBoundSql(mapOf(
            "generationId" to 7L,
            "criteria" to AnalysisCriteria(listOf("SDK"), null, null, null, null),
            "clusterIds" to listOf(42L)
        )).sql.replace(Regex("\\s+"), " ").trim()

        assertTrue(sql.contains("region_value dimension_value"), sql)
        assertTrue(sql.contains("GROUP BY cluster_id,region_value"), sql)
        assertTrue(sql.contains("ORDER BY cluster_id,count DESC,region_value"), sql)
        assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql), sql)
    }

    @Test
    fun `removed reproducibility fields are absent from the public response`() {
        val fields = ProblemClusterItemResponse::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }

        assertFalse(fields.contains("reproducibility"))
        assertFalse(fields.contains("reproducibilityDefinition"))
        assertFalse(fields.contains("regionCoverage"))
    }

    private fun cluster(regions: List<ProblemClusterDimensionSnapshot>) = ProblemClusterSnapshot(
        clusterId = 42,
        canonicalSymptom = "课程卡顿",
        representativeDescription = null,
        representativeTitle = null,
        functionModule = "课堂",
        issueCategory = "性能",
        recordCount = 6,
        userCount = null,
        deviceCount = 0,
        applications = emptyList(),
        firstOccurredAt = null,
        lastOccurredAt = null,
        attachmentCount = 0,
        screenshotCount = 0,
        logCount = 0,
        regions = regions
    )
}
