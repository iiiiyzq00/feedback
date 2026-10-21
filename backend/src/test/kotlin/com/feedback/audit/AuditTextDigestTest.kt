package com.feedback.audit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AuditTextDigestTest {
    @Test
    fun `digest is deterministic after trim and storage value never contains original text`() {
        val original = "  token=synthetic-secret  "
        val first = AuditTextDigester.digest(original)
        val second = AuditTextDigester.digest("token=synthetic-secret")

        assertEquals(second, first)
        assertEquals(64, first.sha256.length)
        assertEquals("token=synthetic-secret".length, first.length)
        assertFalse(first.storageValue().contains("synthetic-secret"))
    }
}
