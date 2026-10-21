package com.feedback.repository.report

import com.feedback.common.exception.ServiceException
import com.feedback.mapper.report.ReportIdempotencyMapper
import com.feedback.mapper.report.ReportLineageMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReportIdempotencyRepositoryTest {
    private val tasks=mockk<ReportIdempotencyMapper>()
    private val repository=ReportIdempotencyRepository(tasks,mockk<ReportLineageMapper>())

    @Test fun `idempotency reservation rejects zero row insert`() {
        every{tasks.insert(any())} returns 0

        assertThrows(ServiceException::class.java){repository.insert("key","MANUAL","base",0)}
    }
}
