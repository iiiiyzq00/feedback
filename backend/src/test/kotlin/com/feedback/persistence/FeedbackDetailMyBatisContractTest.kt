package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeedbackDetailMyBatisContractTest {
    private val resources = listOf(
        "FeedbackDetailSourceMapper.xml",
        "FeedbackDetailContentMapper.xml",
        "FeedbackDetailAiMapper.xml",
        "FeedbackDetailImportBatchMapper.xml",
        "FeedbackTimelineImportRowMapper.xml",
        "FeedbackDetailWorkflowMapper.xml",
        "FeedbackDetailAssignmentMapper.xml",
        "FeedbackDetailUserMapper.xml",
        "FeedbackDetailAuditMapper.xml",
        "FeedbackDetailClusterLinkMapper.xml",
        "FeedbackDetailClusterMapper.xml",
        "FeedbackDetailConfigMapper.xml",
        "FeedbackDetailIdentityMapper.xml",
        "FeedbackDetailSdkMapper.xml",
        "FeedbackDetailJiraMapper.xml",
        "FeedbackDetailQualityMapper.xml",
        "FeedbackDetailRedblackMapper.xml",
        "FeedbackDetailStationMapper.xml"
    ).map { "mybatis/mapper/feedback/detail/$it" }

    @Test
    fun `detail mapper XML parses and contains no cross-table SQL`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        resources.forEach { resource ->
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), resource)
            assertFalse(Regex("\\bEXISTS\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(xml), resource)
            Resources.getResourceAsReader(resource).use { reader ->
                XMLMapperBuilder(reader, configuration, resource, configuration.sqlFragments).parse()
            }
        }

        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.detail.FeedbackDetailSourceMapper.findNextCandidates"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.detail.FeedbackDetailContentMapper.findNavigationFacts"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.detail.FeedbackDetailAuditMapper.insertAccessAudit"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.detail.FeedbackTimelineImportRowMapper.findFirstBySourceId"))
        assertTrue(configuration.hasStatement("com.feedback.mapper.feedback.detail.FeedbackDetailClusterLinkMapper.findTimelineBySourceId"))

        val jiraXml = Resources.getResourceAsReader(
            "mybatis/mapper/feedback/detail/FeedbackDetailJiraMapper.xml"
        ).use { it.readText() }
        assertTrue(Regex("\\bjira_status_raw\\b", RegexOption.IGNORE_CASE).containsMatchIn(jiraXml))
        val stationXml = Resources.getResourceAsReader(
            "mybatis/mapper/feedback/detail/FeedbackDetailStationMapper.xml"
        ).use { it.readText() }
        listOf("product_id", "ingest_status", "processing_stage").forEach { column ->
            assertTrue(Regex("\\b$column\\b", RegexOption.IGNORE_CASE).containsMatchIn(stationXml), column)
        }
        val timelineImportRowXml = Resources.getResourceAsReader(
            "mybatis/mapper/feedback/detail/FeedbackTimelineImportRowMapper.xml"
        ).use { it.readText() }
        assertTrue(timelineImportRowXml.contains("`row_number`"))
    }
}
