package com.feedback.service.alert

import io.mockk.every
import io.mockk.mockk
import com.feedback.model.po.alert.TrendBaselinePo
import com.feedback.repository.alert.AlertTrendBaselineRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class AlertTrendBaselineServiceTest {
    @Test fun `mysql local date time buckets are accepted without timestamp cast`() {
        val repository=mockk<AlertTrendBaselineRepository>()
        val end=LocalDateTime.of(2026,8,4,8,0)
        every{repository.baselines("SDK_VOLUME",listOf("GLOBAL"),any(),any())} returns listOf(TrendBaselinePo("GLOBAL",end.minusHours(2),1L))
        assertEquals(1.0,AlertTrendBaselineService(repository).globalAnomaly("SDK_VOLUME",20.0,end,3.0,20))
    }
}
