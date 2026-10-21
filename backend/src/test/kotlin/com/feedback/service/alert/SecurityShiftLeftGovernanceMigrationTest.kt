package com.feedback.service.alert

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SecurityShiftLeftGovernanceMigrationTest {
    private val sql=Files.readString(Path.of("src/main/resources/db/migration/V170__security_shift_left_governance.sql"))

    @Test fun `all security rules carry prevention class`() {
        listOf(
            "KEY_MATERIAL_EXPOSURE","UNAUTHORIZED_ACCESS_DETECTED","SENSITIVE_DATA_RESIDUAL_BLOCKED",
            "SENSITIVE_DATA_EXPOSURE_CONFIRMED","MASKING_FAILURE_RATE_HIGH","MASKING_RULE_QUALITY_DEGRADED",
            "USER_PRIVACY_EXPOSURE","CHILD_CONTENT_SAFETY","HIGH_RISK_CROSS_CHANNEL","CONTENT_ERROR_SPIKE",
            "MASKING_PIPELINE_BACKLOG","MASKING_REVIEW_SLA_BREACH","DEVICE_SAFETY_STRONG_EVIDENCE",
            "DEVICE_SAFETY_SUSPECTED","USER_SAFETY_IMMINENT"
        ).forEach { code ->
            assertTrue(sql.contains("'$code'"), code)
            assertTrue(sql.contains("preventionClass"), code)
        }
    }

    @Test fun `A class rules are zero tolerance with automated gates and C class is physical world`() {
        assertTrue(sql.contains("'$.preventionClass','A'"))
        assertTrue(sql.contains("automatedGates"))
        assertTrue(sql.contains("CI_SECRET_SCAN"))
        assertTrue(sql.contains("RBAC_MATRIX_TEST"))
        assertTrue(sql.contains("MASKING_PASS_GATE"))
        assertTrue(sql.contains("'$.preventionClass','C'"))
        assertTrue(sql.contains("DEVICE_SAFETY_STRONG_EVIDENCE"))
    }

    @Test fun `shift left does not change governed rule count`() {
        assertFalse(sql.contains("expected-rule-count"), "V170 不得修改规则总数")
        assertTrue(sql.contains("保持 53"))
    }
}
