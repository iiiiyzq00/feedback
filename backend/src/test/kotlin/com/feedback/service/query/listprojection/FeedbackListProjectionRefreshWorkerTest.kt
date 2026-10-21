package com.feedback.service.query.listprojection

import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionDirtyPo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyClaim
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

class FeedbackListProjectionRefreshWorkerTest {
    private val coordinator = mockk<FeedbackListProjectionMutationCoordinator>()
    private val worker = FeedbackListProjectionRefreshWorker(coordinator)
    private val claim = FeedbackListProjectionDirtyClaim(
        "worker",
        listOf(FeedbackListProjectionDirtyPo(sourceId = 7, mutationVersion = 2, retryCount = 0))
    )

    @Test
    fun `worker acknowledges a refreshed claim`() {
        every { coordinator.claimDirty(any(), 200, any(), any()) } returns claim
        every { coordinator.refreshIncremental(FeedbackListProjectionIncrementalRefreshCommand(listOf(7))) } returns
            FeedbackListProjectionIncrementalRefreshResult(1, 1, 0, listOf(3))
        every { coordinator.acknowledgeDirty(claim) } returns 1

        val result = worker.drainOnce(listOf(7))

        assertEquals(FeedbackListProjectionDrainStatus.REFRESHED, result.status)
        assertEquals(1, result.acknowledgedCount)
        verify(exactly = 1) { coordinator.acknowledgeDirty(claim) }
        verify(exactly = 0) { coordinator.deferDirty(any(), any()) }
    }

    @Test
    fun `worker defers without acknowledging when no generation is writable`() {
        every { coordinator.claimDirty(any(), 200, any(), any()) } returns claim
        every { coordinator.refreshIncremental(any()) } returns
            FeedbackListProjectionIncrementalRefreshResult(1, 0, 1, emptyList())
        every { coordinator.deferDirty(claim, any()) } returns 1

        val result = worker.drainOnce()

        assertEquals(FeedbackListProjectionDrainStatus.NO_WRITABLE_GENERATION, result.status)
        verify(exactly = 1) { coordinator.deferDirty(claim, any()) }
        verify(exactly = 0) { coordinator.acknowledgeDirty(any()) }
    }

    @Test
    fun `worker retains failures for bounded retry without exposing exception messages`() {
        every { coordinator.claimDirty(any(), 200, any(), any()) } returns claim
        every { coordinator.refreshIncremental(any()) } throws IllegalStateException("SENSITIVE_SENTINEL")
        every { coordinator.retryDirty(claim, any(), "IllegalStateException", any()) } returns 1

        val result = worker.drainOnce()

        assertEquals(FeedbackListProjectionDrainStatus.RETRY_SCHEDULED, result.status)
        verify(exactly = 1) {
            coordinator.retryDirty(
                claim,
                any(),
                "IllegalStateException",
                match { !it.contains("SENSITIVE_SENTINEL") }
            )
        }
    }

    @Test
    fun `worker fails only rows that independently exhaust the retry limit`() {
        val mixedClaim = FeedbackListProjectionDirtyClaim(
            "worker",
            listOf(
                FeedbackListProjectionDirtyPo(sourceId = 7, mutationVersion = 2, retryCount = 7),
                FeedbackListProjectionDirtyPo(sourceId = 8, mutationVersion = 1, retryCount = 1)
            )
        )
        every { coordinator.claimDirty(any(), 200, any(), any()) } returns mixedClaim
        every { coordinator.refreshIncremental(any()) } throws IllegalStateException("failure")
        every { coordinator.failDirty(any(), any(), any()) } returns 1
        every { coordinator.retryDirty(any(), any(), any(), any()) } returns 1

        val result = worker.drainOnce()

        assertEquals(FeedbackListProjectionDrainStatus.RETRY_SCHEDULED, result.status)
        verify(exactly = 1) { coordinator.failDirty(match { it.sourceIds == listOf(7L) }, any(), any()) }
        verify(exactly = 1) { coordinator.retryDirty(match { it.sourceIds == listOf(8L) }, any(), any(), any()) }
    }

    @Test
    fun `immediate hint runs only after commit`() {
        val method = FeedbackListProjectionRefreshWorker::class.java
            .getDeclaredMethod("afterDirtyCommitted", FeedbackListProjectionDirtyCommittedEvent::class.java)
        val listener = method.getAnnotation(TransactionalEventListener::class.java)
        assertEquals(TransactionPhase.AFTER_COMMIT, listener.phase)
        assertEquals(false, listener.fallbackExecution)
    }
}
