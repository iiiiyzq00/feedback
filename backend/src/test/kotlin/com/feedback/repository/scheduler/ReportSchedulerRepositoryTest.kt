package com.feedback.repository.scheduler

import com.feedback.mapper.scheduler.ReportScheduleRunMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReportSchedulerRepositoryTest {
    private val runs = mockk<ReportScheduleRunMapper>()
    private val repository = ReportSchedulerRepository(runs)

    @Test
    fun `stale completion cannot overwrite schedule run state`() {
        every { runs.succeeded(5, 9) } returns 0

        assertThrows(IllegalStateException::class.java) { repository.succeeded(5, 9) }
    }

    @Test
    fun `stale skip cannot overwrite schedule run state`() {
        every { runs.skipped(5) } returns 0

        assertThrows(IllegalStateException::class.java) { repository.skipped(5) }
    }

    @Test
    fun `stale failure cannot overwrite schedule run state`() {
        every { runs.failed(5, any(), any()) } returns 0

        assertThrows(IllegalStateException::class.java) {
            repository.failed(5, IllegalStateException("boom"))
        }
    }
}
