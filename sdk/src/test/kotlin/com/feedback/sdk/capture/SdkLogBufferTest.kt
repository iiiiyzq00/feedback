package com.feedback.sdk.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkLogBufferTest {
    @Test
    fun `redacts structured and standalone identifiers`() {
        SdkLogBuffer.configure(5, 2048)
        SdkLogBuffer.record(
            "ERROR", "upload_failed",
            "phone=13800138000 email foo@example.com ip 192.168.1.10 id 11010519491231002X"
        )
        val snapshot = SdkLogBuffer.snapshot().toString(Charsets.UTF_8)
        assertFalse(snapshot.contains("13800138000"))
        assertFalse(snapshot.contains("foo@example.com"))
        assertFalse(snapshot.contains("192.168.1.10"))
        assertFalse(snapshot.contains("11010519491231002X"))
        assertTrue(snapshot.contains("[REDACTED]") || snapshot.contains("[ID_CARD]"))
    }

    @Test
    fun `honors remote maximum size`() {
        SdkLogBuffer.configure(5, 1)
        repeat(100) { SdkLogBuffer.record("INFO", "event-$it", "x".repeat(100)) }
        assertTrue(SdkLogBuffer.snapshot().size <= 1024)
    }

    @Test
    fun `window snapshot excludes events recorded after feedback opens`() {
        SdkLogBuffer.configure(3, 2048)
        SdkLogBuffer.record("INFO", "before_feedback_open")
        val openedAt = System.currentTimeMillis()
        Thread.sleep(2)
        SdkLogBuffer.record("INFO", "after_feedback_open")

        val frozen = SdkLogBuffer.snapshotWindow(3, openedAt).toString(Charsets.UTF_8)
        assertTrue(frozen.contains("before_feedback_open"))
        assertFalse(frozen.contains("after_feedback_open"))
    }
}
