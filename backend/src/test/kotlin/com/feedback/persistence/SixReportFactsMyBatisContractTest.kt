package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SixReportFactsMyBatisContractTest {
    private val mapperNames = listOf(
        "ReportFactSourceMapper.xml",
        "ReportFactContentMapper.xml",
        "ReportFactClusterLinkMapper.xml",
        "ReportFactClusterMapper.xml",
        "ReportFactIdentityMapper.xml",
        "ReportFactSdkMapper.xml",
        "ReportFactJiraMapper.xml",
        "ReportFactRedblackMapper.xml",
        "ReportFactAiMapper.xml",
        "ReportFactImportBatchMapper.xml",
        "ReportFactImportRowMapper.xml",
        "ReportFactConfigMapper.xml",
        "ReportFactAlertInstanceMapper.xml",
        "ReportFactAlertRuleMapper.xml",
        "ReportAiCircuitConfigMapper.xml",
        "ReportAiCircuitAuditMapper.xml",
        "ReportAiCircuitIncidentMapper.xml",
    )

    @Test
    fun `report fact mapper XML parses and keeps every mapper on one table`() {
        val configuration = Configuration()
        mapperNames.forEach { name ->
            val resource = "mybatis/mapper/report/$name"
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
            val text = Resources.getResourceAsStream(resource).bufferedReader().use { it.readText() }
            assertFalse(Regex("\\b(JOIN|EXISTS|UNION)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text), name)
            val tables = Regex("\\b(?:FROM|UPDATE|INTO)\\s+(t_[a-z0-9_]+)", RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { it.groupValues[1].lowercase() }
                .toSet()
            assertTrue(tables.size == 1, "$name must access exactly one table but found $tables")
        }
    }
}
