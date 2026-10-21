package com.feedback.demo.universal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapturedScreenshotRegistryTest {
    @Test
    fun screenshotCanOnlyBeConsumedOnce() {
        val bytes = byteArrayOf(1, 2, 3)
        val token = CapturedScreenshotRegistry.issue(bytes)
        assertArrayEquals(bytes, CapturedScreenshotRegistry.consume(token))
        assertNull(CapturedScreenshotRegistry.consume(token))
    }

    @Test
    fun discardClearsPlainBytes() {
        val bytes = byteArrayOf(7, 8, 9)
        val token = CapturedScreenshotRegistry.issue(bytes)
        CapturedScreenshotRegistry.discard(token)
        assertArrayEquals(byteArrayOf(0, 0, 0), bytes)
        assertNull(CapturedScreenshotRegistry.consume(token))
    }
}
