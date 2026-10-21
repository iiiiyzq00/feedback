package com.feedback.service.normalization

import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FaultOccurrenceTimeBackfillRunnerTest {
    private val applicationService = mockk<FaultOccurrenceTimeBackfillApplicationService>()
    private val runner = FaultOccurrenceTimeBackfillRunner(applicationService)

    @Test
    fun `runner advances keyset only after a successful batch`() {
        every { applicationService.processBatch(0) } returns FaultOccurrenceTimeBatchResult(2, 2, candidateCount = 2)
        every { applicationService.processBatch(2) } returns FaultOccurrenceTimeBatchResult(0, 3, candidateCount = 1)
        every { applicationService.processBatch(3) } returns FaultOccurrenceTimeBatchResult.empty(3)

        assertEquals(2, runner.backfill())
        verifyOrder {
            applicationService.processBatch(0)
            applicationService.processBatch(2)
            applicationService.processBatch(3)
        }
    }

    @Test
    fun `runner stops and exposes a failed authority batch`() {
        every { applicationService.processBatch(0) } throws IllegalStateException("DIRTY_MARK_FAILED")

        assertThrows(IllegalStateException::class.java) { runner.backfill() }
    }
}
