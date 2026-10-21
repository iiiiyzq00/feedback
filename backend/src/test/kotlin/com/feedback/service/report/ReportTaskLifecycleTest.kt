package com.feedback.service.report

import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.report.ReportTaskLeasePo
import com.feedback.model.po.report.ReportTaskViewPo
import com.feedback.repository.report.ReportTaskRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReportTaskLifecycleTest {
    private val repository = mockk<ReportTaskRepository>()
    private val lifecycle = ReportTaskLifecycle(repository, jacksonObjectMapper().findAndRegisterModules())

    @Test
    fun `finish deletes generated report when cancellation won the lease race`() {
        every { repository.lock(5) } returns ReportTaskLeasePo(true, "RUNNING", "holder")
        every { repository.cancelCompletedReport(5, 9) } just runs

        assertTrue(lifecycle.finish(5, 9, "holder"))

        verify { repository.cancelCompletedReport(5, 9) }
        verify(exactly = 0) { repository.complete(any(), any()) }
    }

    @Test
    fun `finish attaches report only for the current lease holder`() {
        every { repository.lock(5) } returns ReportTaskLeasePo(false, "RUNNING", "holder")
        every { repository.complete(5, 9) } just runs

        assertFalse(lifecycle.finish(5, 9, "holder"))

        verify { repository.complete(5, 9) }
        assertThrows(ServiceException::class.java) { lifecycle.finish(5, 9, "stale") }
    }

    @Test
    fun `retry remains restricted to the original actor`() {
        every { repository.retryable(5) } returns ReportTaskViewPo(id = 5, status = "RETRY_PENDING", actorId = 7)

        assertThrows(ClientException::class.java) { lifecycle.retryable(5, 8) }
    }
}
