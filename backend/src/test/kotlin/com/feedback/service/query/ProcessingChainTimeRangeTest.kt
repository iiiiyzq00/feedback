package com.feedback.service.query

import com.feedback.common.exception.ClientException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ProcessingChainTimeRangeTest {
    private val now = LocalDateTime.of(2026, 8, 1, 15, 30, 45)

    @Test fun `today starts at local calendar midnight`() {
        assertEquals(LocalDateTime.of(2026, 8, 1, 0, 0), ProcessingChainTimeRange.start("today", now))
    }

    @Test fun `24h is a rolling twenty four hour window`() {
        assertEquals(LocalDateTime.of(2026, 7, 31, 15, 30, 45), ProcessingChainTimeRange.start("24h", now))
    }

    @Test fun `7d is a rolling seven day window`() {
        assertEquals(LocalDateTime.of(2026, 7, 25, 15, 30, 45), ProcessingChainTimeRange.start("7d", now))
    }

    @Test fun `unsupported ranges are rejected`() {
        val error = assertThrows(ClientException::class.java) { ProcessingChainTimeRange.start("30d", now) }
        assertEquals("OPERATIONS_RANGE_INVALID", error.reasonCode)
    }
}
