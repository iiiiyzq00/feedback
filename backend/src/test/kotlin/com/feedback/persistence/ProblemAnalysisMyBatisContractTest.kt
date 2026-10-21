package com.feedback.persistence

import com.feedback.model.query.problem.analysis.AnalysisCriteria
import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProblemAnalysisMyBatisContractTest {
    private val resources = listOf(
        "mybatis/mapper/problem/analysis/ProblemAnalysisMemberMapper.xml",
        "mybatis/mapper/problem/analysis/ProblemAnalysisIdentityMapper.xml",
        "mybatis/mapper/problem/analysis/ClusterFactsMapper.xml",
        "mybatis/mapper/problem/analysis/ClusterFeedbackProjectionMapper.xml"
    )

    @Test
    fun `analysis mappers parse and every statement references one physical business table`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        val governedTables = Regex("\\bt_(?:feedback_cluster_(?:member|identity)_projection|feedback_list_projection|cluster)\\b", RegexOption.IGNORE_CASE)
        resources.forEach { resource ->
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), resource)
            Regex("<(select|insert|update|delete)\\b[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
                .findAll(xml).forEach { statement ->
                    assertTrue(governedTables.findAll(statement.value).map { it.value.lowercase() }.toSet().size <= 1, resource)
                }
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }
        assertTrue(configuration.hasStatement("com.feedback.mapper.problem.analysis.ProblemAnalysisMemberMapper.findClusterPage"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.problem.analysis.ProblemAnalysisIdentityMapper.counts"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.problem.analysis.ClusterFeedbackProjectionMapper.findByIds"))

        val relatedSql = configuration.getMappedStatement(
            "com.feedback.mapper.problem.analysis.ProblemAnalysisMemberMapper.findRelatedFeedbackPage"
        ).getBoundSql(mapOf(
            "generationId" to 7L,
            "criteria" to AnalysisCriteria(listOf("SDK"), null, null, null, null),
            "clusterId" to 42L,
            "excludedSourceId" to 11L,
            "limit" to 10,
            "offset" to 0L
        )).sql.replace(Regex("\\s+"), " ").trim()
        assertTrue(relatedSql.contains("source_id<>?"), relatedSql)
        assertTrue(relatedSql.contains("ORDER BY reported_at DESC,source_id DESC"), relatedSql)
        assertTrue(relatedSql.endsWith("LIMIT ? OFFSET ?"), relatedSql)
    }
}
