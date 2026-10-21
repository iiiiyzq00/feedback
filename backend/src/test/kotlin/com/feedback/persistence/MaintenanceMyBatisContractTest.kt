package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.io.Resources
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MaintenanceMyBatisContractTest {
    private val mappers = mapOf(
        "MaintenanceImportBatchMapper.xml" to "t_import_batch",
        "MaintenanceAiTaskMapper.xml" to "t_ai_task",
        "MaintenanceSdkCipherMapper.xml" to "t_sdk_ingest",
        "MaintenanceAlertInstanceMapper.xml" to "t_alert_instance",
        "MaintenanceImportRowMapper.xml" to "t_import_row",
        "MaintenanceStationRawMapper.xml" to "t_station_feedback_raw",
        "MaintenanceFeedbackContentMapper.xml" to "t_feedback_content",
        "MaintenanceAuthorizedContactMapper.xml" to "t_authorized_contact_vault",
        "MaintenanceImportAttachmentTaskMapper.xml" to "t_import_attachment_task",
        "MaintenanceUploadChunkMapper.xml" to "t_import_upload_chunk",
        "MaintenanceAttachmentMapper.xml" to "t_feedback_attachment"
    )

    @Test
    fun `maintenance mapper XML parses and every mapper stays single table`() {
        val configuration = Configuration().apply { isMapUnderscoreToCamelCase = true }
        mappers.forEach { (name, expectedTable) ->
            val resource = "mybatis/mapper/scheduler/$name"
            val xml = Resources.getResourceAsReader(resource).use { it.readText() }
            assertFalse(Regex("\\b(JOIN|EXISTS|UNION)\\b", RegexOption.IGNORE_CASE).containsMatchIn(xml), name)
            val tables = Regex("\\b(?:FROM|UPDATE|INTO|DELETE\\s+FROM)\\s+(t_[a-z0-9_]+)", RegexOption.IGNORE_CASE)
                .findAll(xml).map { it.groupValues[1].lowercase() }.toSet()
            assertEquals(setOf(expectedTable), tables, name)
            Resources.getResourceAsReader(resource).use {
                XMLMapperBuilder(it, configuration, resource, configuration.sqlFragments).parse()
            }
        }
    }
}
