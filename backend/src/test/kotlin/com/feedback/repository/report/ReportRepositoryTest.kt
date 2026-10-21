package com.feedback.repository.report

import com.feedback.common.exception.ServiceException
import com.feedback.mapper.report.*
import com.feedback.model.po.report.*
import com.feedback.model.query.report.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ReportRepositoryTest {
    private val reports = mockk<ReportMapper>()
    private val notifications = mockk<ReportNotificationMapper>()
    private val occurrences = mockk<ReportOccurrenceMapper>()
    private val tasks = mockk<ReportTaskMapper>()
    private val repository = ReportRepository(reports, notifications, occurrences, tasks)

    @Test
    fun `visible paging continues after an inaccessible batch and keeps exact total`() {
        val firstTime = LocalDateTime.of(2026, 9, 2, 10, 0)
        val first = (1000L downTo 501L).map { ReportListPo(id = it, type = "RISK_BRIEF", generatedAt = firstTime) }
        val second = listOf(
            ReportListPo(id = 500, type = "DAILY_INGEST", generatedAt = firstTime.minusMinutes(1)),
            ReportListPo(id = 499, type = "DAILY_INGEST", generatedAt = firstTime.minusMinutes(2)),
        )
        every { reports.page(null, null, null, 500) } returns first
        every { reports.page(null, firstTime, 501, 500) } returns second

        val result = repository.visiblePage(null, 1, 1) { it.type == "DAILY_INGEST" }

        assertEquals(listOf(500L), result.records.map { it.id })
        assertEquals(2, result.total)
    }

    @Test
    fun `hard delete removes delivery attempts in bounded batches before report dependencies`() {
        every { notifications.idsByReport(9, 0, 500) } returns listOf(10, 11)
        every { occurrences.deleteDeliveryAttempts(listOf(10, 11)) } returns 2
        every { notifications.deleteByReport(9) } returns 2
        every { tasks.deleteByReport(9) } returns 1
        every { reports.delete(9) } returns 1

        repository.hardDelete(9)

        verify { occurrences.deleteDeliveryAttempts(listOf(10, 11)) }
        verify { notifications.deleteByReport(9) }
        verify { tasks.deleteByReport(9) }
        verify { reports.delete(9) }
    }

    @Test
    fun `hard delete stops when notification cleanup count changed`() {
        every { notifications.idsByReport(9, 0, 500) } returns listOf(10, 11)
        every { occurrences.deleteDeliveryAttempts(listOf(10, 11)) } returns 1
        every { notifications.deleteByReport(9) } returns 1

        assertThrows(ServiceException::class.java) { repository.hardDelete(9) }

        verify(exactly = 0) { tasks.deleteByReport(any()) }
        verify(exactly = 0) { reports.delete(any()) }
    }
}
