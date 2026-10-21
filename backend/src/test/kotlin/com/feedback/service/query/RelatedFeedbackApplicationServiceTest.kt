package com.feedback.service.query

import com.feedback.config.FeedbackListReadProperties
import com.feedback.config.ProblemAnalysisReadProperties
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionGenerationPo
import com.feedback.repository.feedback.access.FeedbackAccessFacts
import com.feedback.application.port.problem.analysis.RelatedFeedbackItemSnapshot
import com.feedback.application.port.problem.analysis.RelatedFeedbackReadPort
import com.feedback.application.port.problem.analysis.RelatedFeedbackReadRequest
import com.feedback.application.port.problem.analysis.RelatedFeedbackSnapshot
import com.feedback.model.auth.DataAccessScope
import com.feedback.model.auth.TenantKeys
import com.feedback.security.FeedbackAccessContext
import com.feedback.security.FeedbackAccessGuard
import com.feedback.service.query.listprojection.ActiveGenerationProvider
import com.feedback.service.query.listprojection.ProjectionCapability
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class RelatedFeedbackApplicationServiceTest {
    private val list = FeedbackListReadProperties()
    private val analysis = ProblemAnalysisReadProperties()
    private val generations = mockk<ActiveGenerationProvider>()
    private val reads = mockk<RelatedFeedbackReadPort>()
    private val accessGuard = mockk<FeedbackAccessGuard>()
    private val service = RelatedFeedbackApplicationService(
        list, analysis, generations, reads, accessGuard
    )

    @Test fun `either disabled switch fails closed before generation lookup`() {
        list.readEnabled = true
        allowAccess(1)
        val error = assertThrows(ServiceException::class.java) { service.related(1, 1, 10) }
        assertEquals(503, error.errorCode.httpStatus.value())
        verify(exactly = 0) { generations.require(any()) }
        verify(exactly = 0) { reads.read(any()) }
    }

    @Test fun `related page pins one complete generation and excludes current source`() {
        list.readEnabled = true
        analysis.readEnabled = true
        allowAccess(11)
        every { generations.require(ProjectionCapability.PROBLEM_ANALYSIS) } returns FeedbackListProjectionGenerationPo(id = 7)
        val time = LocalDateTime.of(2026, 8, 30, 12, 0)
        val request = slot<RelatedFeedbackReadRequest>()
        every { reads.read(capture(request)) } returns RelatedFeedbackSnapshot(
            listOf(RelatedFeedbackItemSnapshot(
                13, 42, "SDK", "sdk-13", null, "安全正文", time, BigDecimal("0.91"), null
            )),
            2
        )

        val result = service.related(11, 1, 10)

        assertEquals(2, result.total)
        assertEquals(listOf(13L), result.records.map { it.id })
        assertEquals("安全正文", result.records.single().feedbackContent)
        assertEquals(null, result.records.single().decisionVersion)
        assertEquals(7, request.captured.generationId)
        assertEquals(listOf("SDK"), request.captured.scope.allowedChannels)
        assertEquals(1, request.captured.page)
        assertEquals(10, request.captured.size)
    }

    @Test fun `invalid pagination fails before generation and repository reads`() {
        allowAccess(11)

        assertThrows(ClientException::class.java) { service.related(11, 0, 10) }
        assertThrows(ClientException::class.java) { service.related(11, 1, 51) }

        verify(exactly = 0) { generations.require(any()) }
        verify(exactly = 0) { reads.read(any()) }
    }

    @Test fun `missing safe projection rejects the whole related page`() {
        list.readEnabled = true
        analysis.readEnabled = true
        allowAccess(11)
        every { generations.require(ProjectionCapability.PROBLEM_ANALYSIS) } returns
            FeedbackListProjectionGenerationPo(id = 7)
        every { reads.read(any()) } throws IllegalStateException("FEEDBACK_RELATED_FEEDBACK_PROJECTION_INCOMPLETE")

        val error = assertThrows(IllegalStateException::class.java) { service.related(11, 1, 10) }

        assertEquals("FEEDBACK_RELATED_FEEDBACK_PROJECTION_INCOMPLETE", error.message)
    }

    @Test fun `feedback absent from active cluster projection returns an empty page`() {
        list.readEnabled = true
        analysis.readEnabled = true
        allowAccess(11)
        every { generations.require(ProjectionCapability.PROBLEM_ANALYSIS) } returns
            FeedbackListProjectionGenerationPo(id = 7)
        every { reads.read(any()) } returns RelatedFeedbackSnapshot(emptyList(), 0)

        val result = service.related(11, 2, 10)

        assertEquals(emptyList<RelatedFeedbackItemResponse>(), result.records)
        assertEquals(0, result.total)
        assertEquals(2, result.page)
        assertEquals(10, result.size)
        verify(exactly = 1) { reads.read(any()) }
    }

    private fun allowAccess(sourceId: Long) {
        every { accessGuard.require(sourceId) } returns FeedbackAccessContext(
            // 租户维度不在本用例的 scope 桩里（tenants = null），故取值不参与判定。
            FeedbackAccessFacts(sourceId, "SDK", "PRODUCTION", "课程", null, null, "PASS", false, tenant = TenantKeys.DEFAULT),
            DataAccessScope(setOf("SDK"), setOf("课程"), null)
        )
    }
}
