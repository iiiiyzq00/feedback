package com.feedback.service.report

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ReportRuntimeEvidenceMigrationTest{
    @Test fun `V62 fences report evidence references and outbox idempotency`(){
        val sql=Files.readString(Path.of("src/main/resources/db/migration/V62__report_runtime_evidence_hardening.sql")).lowercase()
        listOf("uk_report_notification_once","report_evidence_orphaned","fk_report_task_report","fk_report_schedule_report","fk_report_generation_key_report").forEach{assertTrue(sql.contains(it),it)}
    }
}
