package com.feedback.service.report

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ReportIdempotencyMigrationTest {
    @Test fun `day8 migration has database uniqueness and durable schedule history`() {
        val sql=Files.readString(Path.of("src/main/resources/db/migration/V53__day8_report_schedule_idempotency.sql"))
        assertTrue(sql.contains("UNIQUE KEY uk_report_generation_key"))
        assertTrue(sql.contains("UNIQUE KEY uk_report_idempotency_key"))
        assertTrue(sql.contains("t_report_schedule_run"))
        assertTrue(sql.contains("SKIPPED_LOCK"))
        assertTrue(sql.contains("previous_report_id"))
    }
}
