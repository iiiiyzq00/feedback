package com.feedback.service.query.listprojection

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionDirtyPo
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionGenerationPo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionControlRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyRepository
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FeedbackListProjectionMutationCoordinatorTest {
    private val repository = mockk<FeedbackListProjectionRepository>(relaxed = true)
    private val synchronizer = mockk<FeedbackListProjectionSynchronizer>()
    private val dirty = mockk<FeedbackListProjectionDirtyRepository>()
    private val control = mockk<FeedbackListProjectionControlRepository>()
    private val verifier = mockk<FeedbackListProjectionGenerationVerifier>()
    private val coordinator = FeedbackListProjectionMutationCoordinator(repository, synchronizer, dirty, control, verifier)

    @Test
    fun `create locks epoch snapshots fence and baseline before inserting unique building`() {
        every { control.lockControl() } returns 6
        every { control.requireActiveActor(5) } just Runs
        every { repository.lockManagedGenerations() } returns emptyList()
        every { repository.sourceFence() } returns 99
        every { repository.sourceCountThroughFence(99) } returns 80
        every { repository.createBuilding("g-1", 99, 80, 5) } returns generation(7, "g-1", "BUILDING").copy(
            buildFenceSourceId = 99, expectedSourceCount = 80
        )

        val result = coordinator.createBuildingSnapshot(FeedbackListProjectionBuildCommand("CREATE", "g-1", 5, 200))

        assertEquals(99L, result.fenceSourceId)
        verifyOrder {
            control.lockControl()
            control.requireActiveActor(5)
            repository.lockManagedGenerations()
            repository.sourceFence()
            repository.sourceCountThroughFence(99)
            repository.createBuilding("g-1", 99, 80, 5)
        }
    }

    @Test
    fun `catch up captures source tail under control lock and extends fence with CAS`() {
        val building = generation(9, "next", "BUILDING")
        every { control.lockControl() } returns 12
        every { control.requireActiveActor(5) } just Runs
        every { repository.lockByKey("next") } returns building
        every { repository.sourceFence() } returns 25
        every { repository.sourceCountThroughFence(25) } returns 24
        every { repository.extendBuildFence(9, 20, 20, 25, 24) } just Runs

        val result = coordinator.prepareBuildingCatchUp(
            FeedbackListProjectionBuildCommand("REFRESH", "next", 5, 200)
        )

        assertEquals(25L, result.fenceSourceId)
        verifyOrder {
            control.lockControl()
            control.requireActiveActor(5)
            repository.lockByKey("next")
            repository.sourceFence()
            repository.sourceCountThroughFence(25)
            repository.extendBuildFence(9, 20, 20, 25, 24)
        }
    }

    @Test
    fun `catch up does not move fence while cursor is still behind`() {
        every { control.lockControl() } returns 12
        every { control.requireActiveActor(5) } just Runs
        every { repository.lockByKey("next") } returns generation(9, "next", "BUILDING").copy(
            buildCursorSourceId = 10
        )

        val result = coordinator.prepareBuildingCatchUp(
            FeedbackListProjectionBuildCommand("REFRESH", "next", 5, 200)
        )

        assertEquals(20L, result.fenceSourceId)
        verify(exactly = 0) { repository.sourceFence() }
        verify(exactly = 0) { repository.extendBuildFence(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `verification requiring source catch up leaves building unchanged`() {
        every { control.lockControl() } returns 12
        every { control.requireActiveActor(5) } just Runs
        every { repository.lockManagedGenerations() } returns listOf(generation(9, "next", "BUILDING"))
        every { dirty.lockFirstDirty() } returns null
        every { repository.sourceFence() } returns 21
        every { repository.sourceCountThroughFence(21) } returns 21

        val error = assertThrows(ServiceException::class.java) {
            coordinator.verifyBuilding(FeedbackListProjectionBuildCommand("VERIFY", "next", 5, 200))
        }
        assertEquals(ErrorCode.FEEDBACK_LIST_PROJECTION_CATCH_UP_REQUIRED, error.errorCode)

        verify(exactly = 0) { verifier.verify(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.markVerified(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { repository.transition(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `activation locks control generations and dirty before any status CAS`() {
        val active = generation(3, "old", "ACTIVE")
        val building = generation(9, "next", "BUILDING").copy(
            verifiedMutationEpoch = 12, contentChecksum = "a".repeat(64), verifiedMemberChecksum = "b".repeat(64),
            verifiedAt = java.time.LocalDateTime.of(2026, 8, 30, 9, 0)
        )
        val verified = verification(9, 12)
        every { control.lockControl() } returns 12
        every { control.requireActiveActor(5) } just Runs
        every { repository.lockManagedGenerations() } returns listOf(active, building)
        every { dirty.lockFirstDirty() } returns null
        every { repository.sourceFence() } returns 20
        every { repository.sourceCountThroughFence(20) } returns 20
        every { verifier.verify(9, 20, 20, 12) } returns verified
        every { verifier.verify(3, 20, 20, 12) } returns verified.copy(generationId = 3)

        val result = coordinator.activateVerified(FeedbackListProjectionActivationCommand("ACTIVATE", "next", 5))

        assertEquals("ACTIVE", result.generationStatus)
        assertEquals(3L, result.previousActiveGenerationId)
        verifyOrder {
            control.lockControl()
            control.requireActiveActor(5)
            repository.lockManagedGenerations()
            dirty.lockFirstDirty()
            repository.sourceFence()
            repository.sourceCountThroughFence(20)
            verifier.verify(9, 20, 20, 12)
            verifier.verify(3, 20, 20, 12)
            repository.prepareActiveForRollback(3, 20, 20, any())
            repository.transition(3, "ACTIVE", "ROLLBACK_READY", null, 12, null, null)
            repository.transition(9, "BUILDING", "ACTIVE", 5, 12, null, null)
        }
    }

    @Test
    fun `dirty activation attempt leaves old active unchanged`() {
        every { control.lockControl() } returns 12
        every { control.requireActiveActor(5) } just Runs
        every { repository.lockManagedGenerations() } returns listOf(
            generation(3, "old", "ACTIVE"), generation(9, "next", "BUILDING")
        )
        every { dirty.lockFirstDirty() } returns FeedbackListProjectionDirtyPo(sourceId = 1)
        assertThrows(ServiceException::class.java) {
            coordinator.activateVerified(FeedbackListProjectionActivationCommand("ACTIVATE", "next", 5))
        }
        verify(exactly = 0) { repository.transition(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `activation requiring source tail catch up leaves old active unchanged`() {
        val active = generation(3, "old", "ACTIVE")
        val building = generation(9, "next", "BUILDING").copy(
            verifiedMutationEpoch = 12, verifiedAt = java.time.LocalDateTime.of(2026, 8, 30, 9, 0)
        )
        every { control.lockControl() } returns 12
        every { control.requireActiveActor(5) } just Runs
        every { repository.lockManagedGenerations() } returns listOf(active, building)
        every { dirty.lockFirstDirty() } returns null
        every { repository.sourceFence() } returns 21
        every { repository.sourceCountThroughFence(21) } returns 21

        val error = assertThrows(ServiceException::class.java) {
            coordinator.activateVerified(FeedbackListProjectionActivationCommand("ACTIVATE", "next", 5))
        }
        assertEquals(ErrorCode.FEEDBACK_LIST_PROJECTION_CATCH_UP_REQUIRED, error.errorCode)

        verify(exactly = 0) { verifier.verify(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.transition(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rollback rejects any epoch change before checksum work or CAS`() {
        every { control.lockControl() } returns 13
        every { control.requireActiveActor(5) } just Runs
        every { repository.lockManagedGenerations() } returns listOf(
            generation(3, "old", "ROLLBACK_READY").copy(cutoverMutationEpoch = 12),
            generation(9, "next", "ACTIVE").copy(cutoverMutationEpoch = 12)
        )
        every { dirty.lockFirstDirty() } returns null
        assertThrows(ServiceException::class.java) {
            coordinator.rollback(FeedbackListProjectionRollbackCommand("ROLLBACK", "old", 5))
        }
        verify(exactly = 0) { verifier.verify(any(), any(), any(), any()) }
        verify(exactly = 0) { repository.transition(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rollback verifies both immutable generations then swaps slots with CAS`() {
        val rollbackReady = generation(3, "old", "ROLLBACK_READY").copy(cutoverMutationEpoch = 12)
        val active = generation(9, "next", "ACTIVE").copy(cutoverMutationEpoch = 12)
        every { control.lockControl() } returns 12
        every { control.requireActiveActor(5) } just Runs
        every { repository.lockManagedGenerations() } returns listOf(rollbackReady, active)
        every { dirty.lockFirstDirty() } returns null
        every { verifier.verify(9, 20, 20, 12) } returns verification(9, 12)
        every { verifier.verify(3, 20, 20, 12) } returns verification(3, 12)

        val result = coordinator.rollback(FeedbackListProjectionRollbackCommand("ROLLBACK", "old", 5))

        assertEquals(3L, result.generationId)
        verifyOrder {
            verifier.verify(9, 20, 20, 12)
            verifier.verify(3, 20, 20, 12)
            repository.transition(
                9, "ACTIVE", "FAILED", null, null,
                "FEEDBACK_LIST_PROJECTION_ROLLED_BACK", "Generation replaced by governed rollback"
            )
            repository.transition(3, "ROLLBACK_READY", "ACTIVE", 5, 12, null, null)
        }
    }

    private fun generation(id: Long, key: String, status: String) = FeedbackListProjectionGenerationPo(
        id = id, generationKey = key, generationStatus = status, buildCursorSourceId = 20,
        buildFenceSourceId = 20, expectedSourceCount = 20, projectedSourceCount = 20,
        rejectedSourceCount = 0, contentChecksum = "a".repeat(64), verifiedMemberChecksum = "b".repeat(64),
        analysisProjectionVersion = 1, verifiedAnalysisChecksum = "0".repeat(64),
        verifiedAnalysisIdentityChecksum = "0".repeat(64), projectedAnalysisIdentityCount = 0
    )

    private fun verification(id: Long, epoch: Long) = FeedbackListProjectionVerificationResult(
        id, epoch, 20, 20, 0, 0, "a".repeat(64), "b".repeat(64)
    )
}
