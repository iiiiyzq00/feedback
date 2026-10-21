package com.feedback.service.attachment

import com.feedback.model.attachment.*

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ClamAvAttachmentSecurityScannerTest {
    @Test
    fun `disabled scanner fails closed and records error metric`() {
        val meters = SimpleMeterRegistry()
        val result = ClamAvAttachmentSecurityScanner(false, "127.0.0.1", 3310, 500, meters)
            .scan(Path.of("does-not-need-to-exist"))

        assertEquals(AttachmentScanResult.Unavailable("ATTACHMENT_SCANNER_DISABLED"), result)
        assertEquals(1.0, meters.counter("attachment.security.scan", "outcome", "error").count())
        assertEquals(1L, meters.timer("attachment.security.scan.duration", "outcome", "error").count())
    }
}
