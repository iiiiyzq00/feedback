package com.feedback.service.query.listprojection

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

class FeedbackListProjectionBackfillApplicationServiceTest {
    @Test
    fun `batch failure closes building with fixed sanitized facts and propagates original error`() {
        val coordinator = mockk<FeedbackListProjectionMutationCoordinator>()
        val service = FeedbackListProjectionBackfillApplicationService(coordinator)
        every { coordinator.prepareBuildingCatchUp(FeedbackListProjectionBuildCommand("REFRESH", "g-1", 8, 200)) } returns
            FeedbackListProjectionBuildResult(1, "BUILDING", 0, 20, 20)
        every { coordinator.refreshNextBatch(FeedbackListProjectionBuildCommand("REFRESH", "g-1", 8, 200)) } throws
            ServiceException(ErrorCode.FEEDBACK_LIST_PROJECTION_BUILD_FAILED, "FEEDBACK_LIST_PROJECTION_BUILD_EXECUTION_FAILED")
        every { coordinator.failBuilding(any(), any(), any()) } just Runs
        assertThrows(ServiceException::class.java) {
            service.refreshNextBatch(FeedbackListProjectionBuildCommand("REFRESH", "g-1", 8, 200))
        }
        verify {
            coordinator.failBuilding(
                "g-1", "FEEDBACK_LIST_PROJECTION_BUILD_BATCH_FAILED",
                "Projection build batch failed; generation closed"
            )
        }
    }

    @Test
    fun `runner bean is absent unless enable is explicitly true`() {
        val condition = FeedbackListProjectionBackfillRunner::class.java.getAnnotation(ConditionalOnProperty::class.java)
        assertFalse(condition.matchIfMissing)
        assertEquals("true", condition.havingValue)
    }

    @Test
    fun `verification execution failure closes building with a fixed code`() {
        val coordinator = mockk<FeedbackListProjectionMutationCoordinator>()
        val service = FeedbackListProjectionBackfillApplicationService(coordinator)
        val command = FeedbackListProjectionBuildCommand("VERIFY", "g-1", 8, 200)
        every { coordinator.verifyBuilding(command) } throws
            ServiceException(ErrorCode.FEEDBACK_LIST_PROJECTION_BUILD_FAILED, "FEEDBACK_LIST_PROJECTION_BUILD_EXECUTION_FAILED")
        every { coordinator.failBuilding(any(), any(), any()) } just Runs

        assertThrows(ServiceException::class.java) { service.verify(command) }
        verify {
            coordinator.failBuilding(
                "g-1", "FEEDBACK_LIST_PROJECTION_VERIFICATION_FAILED",
                "Projection verification failed; generation closed"
            )
        }
    }

    @Test
    fun `catch up required leaves building open for another governed refresh`() {
        val coordinator = mockk<FeedbackListProjectionMutationCoordinator>()
        val service = FeedbackListProjectionBackfillApplicationService(coordinator)
        val command = FeedbackListProjectionBuildCommand("VERIFY", "g-1", 8, 200)
        every { coordinator.verifyBuilding(command) } throws
            ServiceException(ErrorCode.FEEDBACK_LIST_PROJECTION_CATCH_UP_REQUIRED, "FEEDBACK_LIST_PROJECTION_CATCH_UP_REQUIRED")

        val error = assertThrows(ServiceException::class.java) { service.verify(command) }
        assertEquals(ErrorCode.FEEDBACK_LIST_PROJECTION_CATCH_UP_REQUIRED, error.errorCode)
        verify(exactly = 0) { coordinator.failBuilding(any(), any(), any()) }
    }
}
