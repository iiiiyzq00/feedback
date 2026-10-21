package com.feedback.service.jira

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class JiraApiMigrationTest {
    @Test
    fun `V176 adds API batches and durable sync state without secret columns`() {
        val sql = Files.readString(Path.of("src/main/resources/db/migration/V176__jira_api_readonly_sync.sql"))
        assertTrue(sql.contains("ingest_method"))
        assertTrue(sql.contains("CREATE TABLE t_jira_sync_state"))
        assertTrue(sql.contains("watermark_updated_at"))
        assertTrue(sql.contains("lease_expires_at"))
        assertFalse(Regex("(?i)token|password|credential").containsMatchIn(sql.substringAfter("CREATE TABLE t_jira_sync_state")))
    }
}
