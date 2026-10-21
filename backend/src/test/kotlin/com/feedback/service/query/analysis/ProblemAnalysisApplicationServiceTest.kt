package com.feedback.service.query.analysis

import com.feedback.config.ProblemAnalysisReadProperties
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionGenerationPo
import com.feedback.application.port.problem.analysis.ClusterFeedbackItemSnapshot
import com.feedback.application.port.problem.analysis.ClusterFeedbackSnapshot
import com.feedback.application.port.problem.analysis.ClusterReadPort
import com.feedback.application.port.problem.analysis.ProblemAnalysisReadPage
import com.feedback.application.port.problem.analysis.ProblemAnalysisReadPort
import com.feedback.application.port.problem.analysis.ProblemAnalysisReadRequest
import com.feedback.application.port.problem.analysis.ProblemAnalysisSnapshot
import com.feedback.application.port.problem.analysis.ProblemAnalysisTotalsSnapshot
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import com.feedback.service.query.listprojection.ActiveGenerationProvider
import com.feedback.service.query.listprojection.ProjectionCapability
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ProblemAnalysisApplicationServiceTest {
    private val properties = ProblemAnalysisReadProperties()
    private val generations = mockk<ActiveGenerationProvider>()
    private val reads = mockk<ProblemAnalysisReadPort>()
    private val clusterReadPort = mockk<ClusterReadPort>()
    private val permissions = mockk<DataPermissionService>()
    private val principal = mockk<CurrentWebPrincipalProvider>()
    private val service = ProblemAnalysisApplicationService(
        properties, generations, reads, permissions, principal
    )
    private val clusterReads = ClusterReadApplicationService(
        properties, generations, clusterReadPort, permissions, principal
    )
    private val defaultQuery = ProblemAnalysisQuery(
        ProblemAnalysisPeriod(null, null), ProblemAnalysisPage(1, 20), ProblemAnalysisPage(1, 20)
    )

    init {
        every { principal.require() } returns WebPrincipal(
            9, "phase", "", "Phase", setOf("PHASE_ONE_PRODUCT"),
            setOf("access:phase_one_workspace"), authProvider = "SSO"
        )
    }

    @Test fun `workspace rejection happens before analysis switch and generation lookup`() {
        every { principal.require() } returns WebPrincipal(
            9, "local", "", "Local", setOf("OPERATOR"), emptySet(), authProvider = "LOCAL"
        )

        assertThrows(ClientException::class.java) {
            service.analyze(defaultQuery)
        }

        verify(exactly = 0) { generations.require(any()) }
        verify(exactly = 0) { reads.read(any()) }
    }

    @Test fun `default disabled analysis fails before generation and has no fallback`() {
        val error = assertThrows(ServiceException::class.java) {
            service.analyze(defaultQuery)
        }
        assertEquals(503, error.errorCode.httpStatus.value())
        assertEquals("FEEDBACK_PROBLEM_ANALYSIS_READ_DISABLED", error.reasonCode)
        verify(exactly = 0) { generations.require(any()) }
        verify(exactly = 0) { reads.read(any()) }
    }

    @Test fun `all and cross channel clusters keep independent bounded pages`() {
        properties.readEnabled = true
        every { generations.require(ProjectionCapability.PROBLEM_ANALYSIS) } returns
            FeedbackListProjectionGenerationPo(id = 7)
        every { permissions.scope() } returns DataAccessScope(setOf("SDK", "JIRA"), null, null)
        every { reads.read(any()) } returns ProblemAnalysisSnapshot(
            ProblemAnalysisTotalsSnapshot(12, 3), emptyList(), emptyList()
        )

        val result = service.analyze(ProblemAnalysisQuery(
            ProblemAnalysisPeriod(null, null), ProblemAnalysisPage(2, 10), ProblemAnalysisPage(3, 5)
        ))

        assertEquals(12, result.allClusters.total)
        assertEquals(2, result.allClusters.page)
        assertEquals(10, result.allClusters.size)
        assertEquals(3, result.crossChannelClusters.total)
        assertEquals(3, result.crossChannelClusters.page)
        assertEquals(5, result.crossChannelClusters.size)
        verify(exactly = 1) {
            reads.read(match {
                it.generationId == 7L && it.allClusters == ProblemAnalysisReadPage(2, 10) &&
                    it.crossChannelClusters == ProblemAnalysisReadPage(3, 5)
            })
        }
    }

    @Test fun `time and page validation fail before generation or reads`() {
        val invalidQueries = listOf(
            ProblemAnalysisQuery(
                ProblemAnalysisPeriod(LocalDateTime.of(2026, 8, 1, 0, 0), null),
                ProblemAnalysisPage(1, 20), ProblemAnalysisPage(1, 20)
            ),
            ProblemAnalysisQuery(
                ProblemAnalysisPeriod(
                    LocalDateTime.of(2026, 8, 2, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 0)
                ),
                ProblemAnalysisPage(1, 20), ProblemAnalysisPage(1, 20)
            ),
            ProblemAnalysisQuery(
                ProblemAnalysisPeriod(null, null), ProblemAnalysisPage(0, 20), ProblemAnalysisPage(1, 20)
            ),
            ProblemAnalysisQuery(
                ProblemAnalysisPeriod(null, null), ProblemAnalysisPage(1, 20), ProblemAnalysisPage(1, 101)
            )
        )

        invalidQueries.forEach { assertThrows(ClientException::class.java) { service.analyze(it) } }

        verify(exactly = 0) { generations.require(any()) }
        verify(exactly = 0) { reads.read(any()) }
    }

    @Test fun `data scope is normalized to the closed five channels`() {
        properties.readEnabled = true
        every { generations.require(ProjectionCapability.PROBLEM_ANALYSIS) } returns
            FeedbackListProjectionGenerationPo(id = 7)
        every { permissions.scope() } returns DataAccessScope(
            setOf("sdk", "station_feedback", "jira", "quality", "redblack", "retired"),
            setOf("math"),
            setOf("cn-44")
        )
        val captured = slot<ProblemAnalysisReadRequest>()
        every { reads.read(capture(captured)) } returns ProblemAnalysisSnapshot(
            ProblemAnalysisTotalsSnapshot(0, 0), emptyList(), emptyList()
        )

        service.analyze(defaultQuery)

        assertEquals(listOf("JIRA", "QUALITY", "REDBLACK", "SDK", "STATION_FEEDBACK"), captured.captured.scope.allowedChannels)
        assertEquals(listOf("MATH"), captured.captured.scope.modules)
        assertEquals(listOf("CN-44"), captured.captured.scope.regions)
    }

    @Test fun `cluster feedback returns a bounded twenty-row page from the pinned generation`() {
        properties.readEnabled = true
        every { generations.require(ProjectionCapability.PROBLEM_ANALYSIS) } returns FeedbackListProjectionGenerationPo(id = 7)
        every { principal.require() } returns WebPrincipal(
            9, "phase", "", "Phase", setOf("PHASE_ONE_PRODUCT"), setOf("access:phase_one_workspace"), authProvider = "SSO"
        )
        every { permissions.scope() } returns DataAccessScope(setOf("SDK"), null, null)
        every { clusterReadPort.feedback(any()) } returns ClusterFeedbackSnapshot(
            21,
            (1L..20L).map { clusterFeedback(it, "SDK") }
        )

        val result = clusterReads.feedback(ClusterFeedbackQuery(
            42, ProblemAnalysisPage(1, 20), ProblemAnalysisPeriod(null, null)
        ))

        assertEquals(20, result.items.size)
        assertEquals(21, result.total)
        assertEquals(20, result.size)
        verify(exactly = 1) {
            clusterReadPort.feedback(match {
                it.generationId == 7L && it.clusterId == 42L && it.page == ProblemAnalysisReadPage(1, 20)
            })
        }
    }

    @Test fun `legacy report permission retains cluster feedback access but cannot open problem analysis`() {
        properties.readEnabled = true
        every { generations.require(ProjectionCapability.PROBLEM_ANALYSIS) } returns FeedbackListProjectionGenerationPo(id = 7)
        every { principal.require() } returns WebPrincipal(
            9, "report", "", "Report", setOf("AUDITOR"), setOf("access:ai_reports"), authProvider = "LOCAL"
        )
        every { permissions.scope() } returns DataAccessScope(setOf("JIRA"), null, null)
        every { clusterReadPort.feedback(any()) } returns ClusterFeedbackSnapshot(
            1, listOf(clusterFeedback(1, "JIRA"))
        )

        assertEquals(1, clusterReads.feedback(ClusterFeedbackQuery(
            42, ProblemAnalysisPage(1, 20), ProblemAnalysisPeriod(null, null)
        )).items.size)
        assertThrows(ClientException::class.java) {
            service.analyze(defaultQuery)
        }
    }

    private fun clusterFeedback(id: Long, channel: String) = ClusterFeedbackItemSnapshot(
        sourceId = id,
        sourceRecordId = null,
        title = null,
        description = "安全正文 $id",
        sourceChannel = channel,
        reportedAt = null,
        controlledInfoTypes = emptyList(),
        attachmentCount = 0,
        screenshotCount = 0,
        logCount = 0,
        videoCount = 0,
        decisionType = null,
        matchSimilarity = null,
        decisionReason = null,
        aiModel = null,
        aiConfidence = null,
        aiAction = null,
        aiExplanation = null
    )
}
