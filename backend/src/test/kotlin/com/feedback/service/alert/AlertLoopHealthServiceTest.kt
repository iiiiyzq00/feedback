package com.feedback.service.alert

import io.mockk.every
import io.mockk.mockk
import com.feedback.model.po.alert.AlertCategoryCoveragePo
import com.feedback.repository.alert.AlertLoopHealthRepository
import com.feedback.repository.alert.AlertLoopRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlertLoopHealthServiceTest {
    @Test
    fun `health is healthy only when all required risk categories are active and no dead letters remain`() {
        val repository = mockk<AlertLoopHealthRepository>()
        every { repository.coverage() } returns listOf("DATA_INGEST", "DATA_QUALITY", "PRODUCT_TREND", "MASKING").map { AlertCategoryCoveragePo(it,2,1) }
        every { repository.runtime() } returns AlertLoopRuntime(null,1,2,0,0,null)

        val result = AlertLoopHealthService(repository).health()

        assertEquals("HEALTHY", result["status"])
        assertEquals(4, (result["categories"] as List<*>).size)
    }

    @Test
    fun `dead letter makes an otherwise covered loop degraded`() {
        val repository = mockk<AlertLoopHealthRepository>()
        every { repository.coverage() } returns listOf("DATA_INGEST", "DATA_QUALITY", "PRODUCT_TREND", "MASKING").map { AlertCategoryCoveragePo(it,1,1) }
        every { repository.runtime() } returns AlertLoopRuntime(null,0,0,0,1,null)

        assertEquals("DEGRADED", AlertLoopHealthService(repository).health()["status"])
    }
}
