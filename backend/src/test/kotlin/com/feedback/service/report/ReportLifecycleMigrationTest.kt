package com.feedback.service.report

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ReportLifecycleMigrationTest {
 @Test fun `V55 defines report task snapshots circuit and worker indexes`(){val sql=Files.readString(Path.of("src/main/resources/db/migration/V55__day8_report_ai_task_lifecycle.sql"));listOf("t_report_generation_task","PENDING","RUNNING","RETRY_PENDING","COMPLETED","FAILED","lease_expires_at","idx_report_task_claim","prompt_sha256_snapshot","provider_request_id","input_tokens","latency_ms","estimated_cost_usd","t_report_ai_health","HALF_OPEN").forEach{assertTrue(sql.contains(it),it)}}
}
