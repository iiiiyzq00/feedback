package com.feedback.service.alert

import io.mockk.mockk
import io.mockk.verify
import com.feedback.repository.alert.AlertReviewGovernanceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlertReviewGovernanceMetricServiceTest {
    private val repository=mockk<AlertReviewGovernanceRepository>(relaxed=true)

    @Test fun `development mode suppresses review SLA breach metrics without querying deadlines`() {
        val service=AlertReviewGovernanceMetricService(repository,slaEnabled=false)

        assertEquals(0.0,service.clusterSlaBreaches())
        assertEquals(0.0,service.maskingSlaBreaches())
        verify(exactly=0) { repository.clusterBreaches(any()) }
        verify(exactly=0) { repository.maskingBreaches(any()) }
    }
}
