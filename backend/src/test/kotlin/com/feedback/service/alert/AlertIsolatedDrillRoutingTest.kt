package com.feedback.service.alert

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlertIsolatedDrillRoutingTest {
    @Test
    fun `ANY drill routes from the condition that actually matched`() {
        val evaluations = listOf(
            mapOf<String, Any>("metric" to "MASKING_BACKLOG_COUNT", "matched" to true),
            mapOf<String, Any>("metric" to "MASKING_OLDEST_PENDING_MINUTES", "matched" to false)
        )

        assertEquals(0, drillPrimaryConditionIndex(evaluations))
    }

    @Test
    fun `non matching drill keeps deterministic fallback without claiming a match`() {
        val evaluations = listOf(
            mapOf<String, Any>("metric" to "MASKING_BACKLOG_COUNT", "matched" to false),
            mapOf<String, Any>("metric" to "MASKING_OLDEST_PENDING_MINUTES", "matched" to false)
        )

        assertEquals(1, drillPrimaryConditionIndex(evaluations))
    }
}
