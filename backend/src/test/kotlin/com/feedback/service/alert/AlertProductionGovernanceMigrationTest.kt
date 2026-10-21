package com.feedback.service.alert

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AlertProductionGovernanceMigrationTest {
    @Test fun `V60 binds rule contracts and production provider acceptance`() {
        val sql=Files.readString(Path.of("src/main/resources/db/migration/V60__alert_contract_provider_acceptance.sql"))
        listOf("t_alert_data_contract","t_alert_rule_contract_link","t_notification_provider_acceptance","validation_evidence_sha256",
            "idempotency_supported","daily_quota","APPROVED","SUSPENDED").forEach{assertTrue(sql.lowercase().contains(it.lowercase()),it)}
    }
}
