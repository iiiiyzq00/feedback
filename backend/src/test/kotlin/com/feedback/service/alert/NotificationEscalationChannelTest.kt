package com.feedback.service.alert

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationEscalationChannelTest {
    @Test
    fun `public escalation sends an interactive card to the configured alert group`() {
        assertEquals(listOf("FEISHU_CARD", "EMAIL", "IN_APP"), PUBLIC_ESCALATION_CHANNELS)
        assertTrue("FEISHU_CARD" in PUBLIC_ESCALATION_CHANNELS)
        assertFalse("FEISHU_ALL" in PUBLIC_ESCALATION_CHANNELS)
    }
}
