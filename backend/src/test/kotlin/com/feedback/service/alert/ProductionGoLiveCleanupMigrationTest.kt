package com.feedback.service.alert

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ProductionGoLiveCleanupMigrationTest {
    private val sql=Files.readString(Path.of("src/main/resources/db/migration/V171__production_go_live_alert_cleanup.sql"))

    @Test fun `self monitoring enabled legacy retired and drafts deleted`() {
        assertTrue(sql.contains("SYSTEM_NOTIFY_BACKLOG"))
        assertTrue(sql.contains("SYSTEM_NOTIFY_DEAD_LETTER"))
        assertTrue(sql.contains("SYSTEM_NOTIFY_FAILURE_RATE"))
        assertTrue(sql.contains("SYSTEM_NOTIFY_OLDEST_PENDING"))
        assertTrue(sql.contains("ALERT_RULE_CATALOG"))
        assertTrue(sql.contains("'system-notify-v1'"))
        assertTrue(sql.contains("is_enabled = 1"))
        assertTrue(sql.contains("DATA_QUALITY_THRESHOLD_BREACH"))
        assertTrue(sql.contains("'RETIRED'"))
        assertTrue(sql.contains("DELETE FROM t_alert_rule"))
        assertTrue(sql.contains("RULE\\_%"))
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM t_alert_instance"))
        assertTrue(sql.contains("'57'"))
        assertTrue(sql.contains("expected-rule-count"))
    }

    @Test fun `cleanup raises governed rule count to 57 with self monitoring in catalog`() {
        assertTrue(sql.contains("53 → 57"))
        assertTrue(sql.contains("implementationEvidenceSha256"))
        assertTrue(sql.contains("'平台'"))
    }
}
