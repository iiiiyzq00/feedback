package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ImportCoreMyBatisContractTest {
    private val tables = mapOf(
        "ImportBatchMapper.xml" to "t_import_batch",
        "ImportRowMapper.xml" to "t_import_row",
        "ImportTemplateMapper.xml" to "t_import_template",
        "ImportTemplateConfigMapper.xml" to "t_system_config",
        "ImportAttachmentTaskMapper.xml" to "t_import_attachment_task",
        "ImportUploadSessionMapper.xml" to "t_import_row",
        "ImportUploadChunkMapper.xml" to "t_import_upload_chunk",
        "ImportGovernanceMapper.xml" to "t_import_batch",
        "ImportReconciliationBatchMapper.xml" to "t_import_batch",
        "ImportReconciliationRowMapper.xml" to "t_import_row",
        "ImportReconciliationSourceMapper.xml" to "t_feedback_source",
        "ImportReconciliationContentMapper.xml" to "t_feedback_content",
        "ImportReconciliationJiraMapper.xml" to "t_channel_jira",
        "ImportQualityBatchMapper.xml" to "t_import_batch",
        "ImportQualityRowMapper.xml" to "t_import_row",
        "ImportQualitySourceMapper.xml" to "t_feedback_source",
        "ImportQualityContentMapper.xml" to "t_feedback_content",
        "ImportQualityLinkMapper.xml" to "t_cluster_link",
        "ChannelJiraMapper.xml" to "t_channel_jira",
        "ChannelQualityMapper.xml" to "t_channel_quality",
        "ChannelRedblackMapper.xml" to "t_channel_redblack",
        "UnifiedQualitySourceMapper.xml" to "t_feedback_source",
        "UnifiedQualityContentMapper.xml" to "t_feedback_content",
        "UnifiedQualitySdkMapper.xml" to "t_channel_sdk",
        "UnifiedQualityStationMapper.xml" to "t_channel_station_feedback",
        "UnifiedQualityBatchMapper.xml" to "t_import_batch",
        "UnifiedQualityImportRowMapper.xml" to "t_import_row",
        "UnifiedQualityLinkMapper.xml" to "t_cluster_link",
        "UnifiedQualitySnapshotMapper.xml" to "t_alert_occurrence",
        "UnifiedQualityAlertRuleMapper.xml" to "t_alert_rule",
        "UnifiedQualityAlertInstanceMapper.xml" to "t_alert_instance",
        "ImportFeedbackSourceLookupMapper.xml" to "t_feedback_source",
        "ImportRollbackSourceMapper.xml" to "t_feedback_source",
        "ImportRollbackLinkMapper.xml" to "t_cluster_link",
        "ImportRollbackContentMapper.xml" to "t_feedback_content",
        "ImportRollbackClusterMapper.xml" to "t_cluster"
    )

    @Test
    fun `import mapper XML parses and every statement remains single table`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        tables.forEach { (name, expectedTable) ->
            val resource = "mybatis/mapper/imports/$name"
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\b(JOIN|EXISTS)\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            val statementTables = Regex(
                "\\b(?:FROM|UPDATE|INTO|DELETE\\s+FROM)\\s+(t_[a-z0-9_]+)",
                RegexOption.IGNORE_CASE
            ).findAll(xml).map { it.groupValues[1].lowercase() }.toSet()
            assertTrue(statementTables == setOf(expectedTable), "$name:$statementTables")
            Resources.getResourceAsReader(resource).use {
                XMLMapperBuilder(it, configuration, resource, configuration.sqlFragments).parse()
            }
        }
    }

    @Test
    fun `import service and rollback repository contain no direct persistence escape hatch`() {
        listOf(
            "src/main/kotlin/com/feedback/service/imports/ImportBatchService.kt",
            "src/main/kotlin/com/feedback/service/imports/ImportBatchWorker.kt",
            "src/main/kotlin/com/feedback/service/imports/ImportChunkUploadService.kt",
            "src/main/kotlin/com/feedback/service/imports/ImportTemplateService.kt",
            "src/main/kotlin/com/feedback/service/imports/ImportBatchGovernanceService.kt",
            "src/main/kotlin/com/feedback/service/imports/ImportReconciliationExportService.kt",
            "src/main/kotlin/com/feedback/service/imports/ImportQualityService.kt",
            "src/main/kotlin/com/feedback/repository/imports/ImportRollbackRepository.kt"
            ,"src/main/kotlin/com/feedback/repository/ChannelImportRepositories.kt"
            ,"src/main/kotlin/com/feedback/model/jira/ChannelJira.kt"
            ,"src/main/kotlin/com/feedback/model/channel/ChannelQuality.kt"
            ,"src/main/kotlin/com/feedback/model/channel/ChannelRedblack.kt"
            ,"src/main/kotlin/com/feedback/service/imports/UnifiedDataQualityService.kt"
        ).forEach { path ->
            val source = Files.readString(Path.of(path))
            listOf(
                "JdbcTemplate", "NamedParameterJdbcTemplate", "EntityManager", "JpaRepository",
                "createNativeQuery", "nativeQuery"
            ).forEach { forbidden -> assertFalse(source.contains(forbidden), "$path:$forbidden") }
            assertFalse(
                Regex(
                    "\\b(SELECT\\s+.+\\s+FROM|INSERT\\s+INTO|UPDATE\\s+t_[a-z0-9_]+|DELETE\\s+FROM)\\b",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                ).containsMatchIn(source),
                path
            )
        }
    }

    @Test
    fun `unified quality maps nullable mapping error codes to a non-null boolean fact`() {
        val xml = Resources.getResourceAsReader("mybatis/mapper/imports/UnifiedQualityContentMapper.xml").use { it.readText() }

        assertTrue(xml.contains("NULLIF(TRIM(mapping_error), '') IS NULL"))
        assertTrue(xml.contains("END AS mapping_error"))
    }
}
