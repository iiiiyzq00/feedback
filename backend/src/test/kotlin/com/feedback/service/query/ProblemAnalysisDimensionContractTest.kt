package com.feedback.service.query

import com.feedback.common.FeedbackChannel
import com.feedback.common.LogicalChannel
import com.feedback.model.query.problem.analysis.AnalysisCriteria
import com.feedback.service.query.analysis.ProblemAnalysisApplicationService
import com.feedback.service.query.analysis.ProblemAnalysisResponse
import com.feedback.service.query.analysis.ProblemAnalysisQuery
import com.feedback.service.query.analysis.ProblemClusterItemResponse
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** 问题分析维度的行为、公开结构和真实 MyBatis 动态 SQL 契约。 */
class ProblemAnalysisDimensionContractTest {
    @Test
    fun `SDK and station map to one learning machine channel`() {
        assertEquals(LogicalChannel.LEARNING_MACHINE, FeedbackChannel.SDK.logicalChannel)
        assertEquals(LogicalChannel.LEARNING_MACHINE, FeedbackChannel.STATION_FEEDBACK.logicalChannel)
        assertEquals(LogicalChannel.JIRA, FeedbackChannel.JIRA.logicalChannel)
    }

    @Test
    fun `cross channel aggregation counts distinct logical channels in one safe projection`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        val resource = "mybatis/mapper/problem/analysis/ProblemAnalysisMemberMapper.xml"
        Resources.getResourceAsReader(resource).use { reader ->
            XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
        }
        val sql = configuration.getMappedStatement(
            "com.feedback.mapper.problem.analysis.ProblemAnalysisMemberMapper.findClusterPage"
        ).getBoundSql(mapOf(
            "generationId" to 7L,
            "criteria" to AnalysisCriteria(listOf("SDK", "STATION_FEEDBACK"), null, null, null, null),
            "crossChannelOnly" to true,
            "limit" to 20,
            "offset" to 0L
        )).sql.replace(Regex("\\s+"), " ").trim()

        assertTrue(sql.contains("COUNT(DISTINCT logical_channel) logical_channel_count"), sql)
        assertTrue(sql.contains("HAVING COUNT(DISTINCT logical_channel)>1"), sql)
        assertTrue(sql.contains("FROM t_phase_one_feedback_cluster_member_projection"), sql)
        assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql), sql)
    }

    @Test
    fun `problem analysis publishes the safe dimension contract and projection application service`() {
        val fields = ProblemClusterItemResponse::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }
        assertTrue(fields.containsAll(setOf(
            "attachmentCount", "screenshotCount", "logCount", "affectedApplications", "regions",
            "userCount", "deviceCount", "firstOccurredAt", "lastOccurredAt"
        )))
        assertFalse(fields.contains("affectedVersions"))
        assertFalse(fields.contains("reproducibility"))

        val method = ProblemAnalysisApplicationService::class.java
            .getDeclaredMethod("analyze", ProblemAnalysisQuery::class.java)
        assertEquals(ProblemAnalysisResponse::class.java, method.returnType)
        assertTrue(Files.notExists(Path.of("src/main/kotlin/com/feedback/service/query/DashboardQueryService.kt")))
    }
}
