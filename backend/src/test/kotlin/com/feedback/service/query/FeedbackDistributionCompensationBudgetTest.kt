package com.feedback.service.query

import com.feedback.common.exception.ClientException
import com.feedback.repository.query.DistributionCompensationRepository
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FeedbackDistributionCompensationBudgetTest {
    private val repository = mockk<DistributionCompensationRepository>()
    private val gateway = mockk<DistributionReceiptGateway>()
    private val service = FeedbackDistributionCompensationService(repository, gateway)

    @Test
    fun `scheduler scans reject zero and oversized limits before querying`() {
        listOf(0, 21).forEach { limit ->
            assertThrows(ClientException::class.java) { service.reconcileUnknown(limit) }
            assertThrows(ClientException::class.java) { service.dueCompensationBatchIds(limit) }
        }

        verify(exactly = 0) { repository.unknownKeys(any()) }
        verify(exactly = 0) { repository.dueBatchIds(any()) }
        verify(exactly = 0) { gateway.lookup(any()) }
    }
}
