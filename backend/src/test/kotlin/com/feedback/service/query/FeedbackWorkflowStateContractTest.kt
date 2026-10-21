package com.feedback.service.query

import com.feedback.service.query.listprojection.FeedbackListRowResponse
import com.feedback.service.query.listprojection.FeedbackListStatsResponse
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FeedbackWorkflowStateContractTest {
    @Test
    fun `V200 creates unified processing and independent distribution state`() {
        val sql=Files.readString(Path.of("src/main/resources/db/migration/V200__unify_feedback_processing_and_distribution_status.sql"))
        listOf("t_feedback_workflow_state","processing_status","distribution_status","source_status_raw","status_mapping_version",
            "NEW","DISTRIBUTED","PROCESSING","SUSPENDED","RESOLVED","CLOSED",
            "UNASSIGNED","NOTIFYING","PENDING_ACCEPTANCE","ACCEPTED","ROLLED_BACK","CANCELLED")
            .forEach { assertTrue(sql.contains(it),it) }
        val maintenance=Files.readString(Path.of("src/main/resources/db/migration/V201__maintain_source_status_workflow_mapping.sql"))
        listOf("trg_feedback_content_workflow_default","trg_channel_jira_workflow_mapping","trg_channel_station_workflow_mapping",
            "processing_status_origin='SOURCE_MAPPING'").forEach { assertTrue(maintenance.contains(it),it) }
        val correction=Files.readString(Path.of("src/main/resources/db/migration/V202__correct_jira_processing_status_mapping.sql"))
        listOf("jira-processing-status-v2","'SUSPENDED'","'PROCESSING'","'RESOLVED'","'CLOSED'","已挂起","等待第三方")
            .forEach { assertTrue(correction.contains(it),it) }
    }

    @Test
    fun `business query exposes only processing and distribution status`() {
        val fields = FeedbackListRowResponse::class.java.declaredFields.associateBy { it.name }

        assertTrue(fields.containsKey("status"))
        assertTrue(fields.containsKey("distributionStatus"))
        assertFalse(fields.containsKey("sourceStatus"))
        assertFalse(fields.containsKey("platformStatus"))
    }

    @Test
    fun `channel workbench exposes actionable statistics instead of duplicated quality metrics`() {
        val fields = FeedbackListStatsResponse::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }

        assertTrue(fields.containsAll(setOf(
            "channelCount", "unassignedCount", "activeCount", "resolvedCount", "withEvidenceCount",
            "positiveCount", "negativeCount"
        )))
        assertFalse(fields.contains("maskingPassRate"))
        assertFalse(fields.contains("qualityScore"))
    }
}
