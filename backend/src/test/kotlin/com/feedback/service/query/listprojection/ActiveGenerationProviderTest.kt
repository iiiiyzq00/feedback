package com.feedback.service.query.listprojection

import com.feedback.common.exception.ServiceException
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionGenerationPo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionReadRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ActiveGenerationProviderTest {
    private val repository = mockk<FeedbackListProjectionReadRepository>()
    private val provider = ActiveGenerationProvider(repository)

    @Test fun `analysis requires its version and both additional checksums`() {
        every { repository.activeGenerations() } returns listOf(active().copy(verifiedAnalysisChecksum = null))
        val error = assertThrows(ServiceException::class.java) {
            provider.require(ProjectionCapability.PROBLEM_ANALYSIS)
        }
        assertEquals(503, error.errorCode.httpStatus.value())
        assertEquals("FEEDBACK_PROBLEM_ANALYSIS_GENERATION_INCOMPLETE", error.reasonCode)
    }

    @Test fun `list and analysis pin the same fully verified active generation`() {
        every { repository.activeGenerations() } returns listOf(active())
        assertEquals(7, provider.require(ProjectionCapability.LIST).id)
        assertEquals(7, provider.require(ProjectionCapability.PROBLEM_ANALYSIS).id)
    }

    private fun active() = FeedbackListProjectionGenerationPo(
        id = 7, generationStatus = "ACTIVE", buildCursorSourceId = 10, buildFenceSourceId = 10,
        expectedSourceCount = 10, projectedSourceCount = 9, rejectedSourceCount = 1,
        contentChecksum = "a".repeat(64), verifiedMemberChecksum = "b".repeat(64), verifiedMutationEpoch = 4,
        cutoverMutationEpoch = 4, verifiedAt = LocalDateTime.now(), activatedBy = 1, activatedAt = LocalDateTime.now(),
        analysisProjectionVersion = 1, verifiedAnalysisChecksum = "c".repeat(64),
        verifiedAnalysisIdentityChecksum = "d".repeat(64), projectedAnalysisIdentityCount = 2
    )
}
