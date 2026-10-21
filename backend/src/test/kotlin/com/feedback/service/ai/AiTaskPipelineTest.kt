package com.feedback.service.ai

import com.feedback.service.masking.OutboundPrivacyException
import com.feedback.repository.ai.AiPipelineRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiTaskPipelineTest {
    @Test
    fun `disabled feedback classify enqueue scheduler does not query or enqueue`() {
        val repository = mockk<AiPipelineRepository>()
        val service = mockk<AiTaskService>()

        AiEnqueueScheduler(repository, service, enabled = false).enqueuePassRecords()

        verify(exactly = 0) { repository.enqueueSourceIds(any()) }
        verify(exactly = 0) { service.enqueue(any()) }
    }

    @Test
    fun `enabled feedback classify enqueue scheduler forwards pass records`() {
        val repository = mockk<AiPipelineRepository>()
        val service = mockk<AiTaskService>()
        every { repository.enqueueSourceIds(100) } returns listOf(7L, 8L)
        every { service.enqueue(any()) } returns null

        AiEnqueueScheduler(repository, service, enabled = true).enqueuePassRecords()

        verify(exactly = 1) { repository.enqueueSourceIds(100) }
        verify(exactly = 1) { service.enqueue(7L) }
        verify(exactly = 1) { service.enqueue(8L) }
    }

    @Test
    fun `disabled feedback classify worker scheduler does not claim`() {
        val worker = mockk<AiWorker>()

        AiWorkerScheduler(worker, enabled = false).runPending()

        verify(exactly = 0) { worker.runPending() }
    }

    @Test
    fun `enabled feedback classify worker scheduler forwards pending work`() {
        val worker = mockk<AiWorker>(relaxed = true)

        AiWorkerScheduler(worker, enabled = true).runPending()

        verify(exactly = 1) { worker.runPending() }
    }

    @Test
    fun `outbound privacy rejection cancels task instead of retrying`() {
        val repository = mockk<AiPipelineRepository>(relaxed = true)

        AiTaskClaimService(repository).retry(42L, "worker-1", 9L, OutboundPrivacyException("LLM_CLASSIFICATION"))

        // 失败写回必须带上领取时的 generation，否则旧租约的失败会覆盖新租约的状态。
        verify(exactly = 1) { repository.retry(42L, "worker-1", 9L, "OutboundPrivacyException", true) }
    }

    @Test
    fun `ordinary failures honor max retries and close terminal attempts`() {
        val repository = mockk<AiPipelineRepository>(relaxed = true)

        AiTaskClaimService(repository).retry(42L, "worker-1", 9L, IllegalStateException("provider unavailable"))

        verify(exactly = 1) { repository.retry(42L, "worker-1", 9L, "IllegalStateException", false) }
    }
}
