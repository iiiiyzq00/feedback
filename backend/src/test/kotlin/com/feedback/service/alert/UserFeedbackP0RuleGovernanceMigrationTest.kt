package com.feedback.service.alert

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class UserFeedbackP0RuleGovernanceMigrationTest {
    private val sql=Files.readString(Path.of("src/main/resources/db/migration/V169__user_feedback_p0_rule_governance.sql"))

    @Test fun `catalog contains all user feedback P0 rules with p0 non silenceable`() {
        listOf(
            "USER_SAFETY_IMMINENT","USER_PRIVACY_EXPOSURE","DEVICE_BRICKED_AFTER_UPGRADE",
            "USER_DATA_LOSS_IRRECOVERABLE","CHILD_CONTENT_SAFETY","VERSION_BLOCKING_REGRESSION",
            "CRITICAL_CLUSTER_SURGE","URGENT_FEEDBACK_COLLECTIVE_TIMEOUT","CRITICAL_RESOLVED_RECURRENCE",
            "DEVICE_MODEL_WIDE_FAILURE","PUBLIC_NEGATIVE_FLARE"
        ).forEach { code ->
            assertTrue(sql.contains(code),code)
            assertTrue(sql.contains("'$code'"),"catalog insert for $code")
        }
        assertTrue(sql.contains("'P0'"))
        assertTrue(sql.contains("p0NonSilenceable"))
        assertTrue(sql.contains("ALERT_RULE_CATALOG"))
        assertTrue(sql.contains("CONTRACT_REQUIRED"))
        assertTrue(sql.contains("'p0NonSilenceable',1"))
        assertTrue(sql.contains("'sourceReference','用户反馈P0矩阵"))
    }

    @Test fun `shadow runtime rules are registered disabled and governed count is 53`() {
        assertTrue(sql.contains("'SHADOW'"))
        assertTrue(sql.contains("is_enabled)"))
        assertTrue(sql.contains("0,0 WHERE NOT EXISTS"))
        assertTrue(sql.contains("ordinarySilenceAllowed",false))
        assertTrue(sql.contains("'53'"))
        assertTrue(sql.contains("expected-rule-count"))
    }

    @Test fun `data contracts and rule links are auto derived from catalog dependencies`() {
        assertTrue(sql.contains("JSON_TABLE"))
        assertTrue(sql.contains("ALERT_DATA_CONTRACT"))
        assertTrue(sql.contains("ALERT_RULE_CONTRACT"))
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"))
    }
}
