package com.feedback.repository.problem.analysis

import com.feedback.application.port.problem.analysis.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.problem.analysis.ClusterFactsMapper
import com.feedback.mapper.problem.analysis.ClusterFeedbackProjectionMapper
import com.feedback.mapper.problem.analysis.ProblemAnalysisIdentityMapper
import com.feedback.mapper.problem.analysis.ProblemAnalysisMemberMapper
import com.feedback.model.po.problem.analysis.ClusterFactsPo
import com.feedback.model.po.problem.analysis.ClusterFeedbackProjectionPo
import com.feedback.model.po.problem.analysis.ClusterRepresentativePo
import com.feedback.model.po.problem.analysis.ProblemClusterPo
import com.feedback.model.po.problem.analysis.ProblemDimensionPo
import com.feedback.model.po.problem.analysis.ProblemIdentityCountPo
import com.feedback.model.po.problem.analysis.ProblemTotalsPo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** 验证问题分析组合读取只按两页簇 ID 做有界批量补齐，不按行触发 N+1。 */
class ProblemAnalysisReadRepositoryTest {
    private val members = mockk<ProblemAnalysisMemberMapper>()
    private val identities = mockk<ProblemAnalysisIdentityMapper>()
    private val clusters = mockk<ClusterFactsMapper>()
    private val projections = mockk<ClusterFeedbackProjectionMapper>()
    private val repository = ProblemAnalysisReadRepository(
        ProblemAnalysisFactsRepository(members, identities, clusters, projections),
        jacksonObjectMapper()
    )

    @Test
    fun `two pages share one deduplicated enrichment batch and preserve page order`() {
        every { members.totals(7, any()) } returns ProblemTotalsPo(2, 1)
        every { members.findClusterPage(7, any(), false, 10, 10) } returns listOf(cluster(1), cluster(2))
        every { members.findClusterPage(7, any(), true, 5, 10) } returns listOf(cluster(2))
        every { clusters.findByIds(listOf(1L, 2L)) } returns listOf(
            ClusterFactsPo(id = 1, name = "一", canonicalSymptom = "现象一"),
            ClusterFactsPo(id = 2, name = "二", canonicalSymptom = "现象二")
        )
        every { identities.counts(7, any(), listOf(1L, 2L)) } returns listOf(
            ProblemIdentityCountPo(1, 3, 4), ProblemIdentityCountPo(2, null, 5)
        )
        every { members.findApplicationDimensions(7, any(), listOf(1L, 2L)) } returns listOf(
            ProblemDimensionPo(1, "课堂", 6)
        )
        every { members.findRegionDimensions(7, any(), listOf(1L, 2L)) } returns listOf(
            ProblemDimensionPo(2, "广东", 7)
        )
        every { members.findRepresentatives(7, any(), listOf(1L, 2L)) } returns listOf(
            ClusterRepresentativePo(1, 101), ClusterRepresentativePo(2, 102)
        )
        every { projections.findByIds(7, listOf(101L, 102L)) } returns listOf(
            ClusterFeedbackProjectionPo(101, title = "标题一"),
            ClusterFeedbackProjectionPo(102, description = "正文二")
        )

        val snapshot = repository.read(
            ProblemAnalysisReadRequest(
                7,
                ProblemAnalysisReadScope(listOf("SDK", "JIRA"), null, null),
                ProblemAnalysisReadPeriod(null, null),
                ProblemAnalysisReadPage(2, 10),
                ProblemAnalysisReadPage(3, 5)
            )
        )

        assertEquals(listOf(1L, 2L), snapshot.allClusters.map { it.clusterId })
        assertEquals(listOf(2L), snapshot.crossChannelClusters.map { it.clusterId })
        assertEquals("标题一", snapshot.allClusters[0].representativeTitle)
        assertEquals("正文二", snapshot.crossChannelClusters[0].representativeDescription)
        assertEquals(3, snapshot.allClusters[0].userCount)
        assertEquals(5, snapshot.crossChannelClusters[0].deviceCount)
        verify(exactly = 1) { clusters.findByIds(listOf(1L, 2L)) }
        verify(exactly = 1) { identities.counts(7, any(), listOf(1L, 2L)) }
        verify(exactly = 1) { members.findApplicationDimensions(7, any(), listOf(1L, 2L)) }
        verify(exactly = 1) { members.findRegionDimensions(7, any(), listOf(1L, 2L)) }
        verify(exactly = 1) { members.findRepresentatives(7, any(), listOf(1L, 2L)) }
        verify(exactly = 1) { projections.findByIds(7, listOf(101L, 102L)) }
    }

    @Test
    fun `cluster detail uses zero devices when the identity projection has no row`() {
        every { members.findClusterSummary(7, any(), 42) } returns cluster(42)
        every { clusters.findByIds(listOf(42L)) } returns listOf(
            ClusterFactsPo(id = 42, name = "课程卡顿", status = "ACTIVE")
        )
        every { members.findRepresentatives(7, any(), listOf(42L)) } returns emptyList()
        every { identities.counts(7, any(), listOf(42L)) } returns emptyList()

        val snapshot = repository.detail(ClusterDetailReadRequest(
            generationId = 7,
            scope = ProblemAnalysisReadScope(listOf("SDK"), null, null),
            clusterId = 42,
            period = ProblemAnalysisReadPeriod(null, null)
        ))

        assertEquals(0, snapshot?.deviceCount)
        assertEquals(null, snapshot?.userCount)
    }

    @Test
    fun `invalid repository input uses the shared client exception contract`() {
        val pagination = assertThrows(ClientException::class.java) {
            repository.read(ProblemAnalysisReadRequest(
                7,
                ProblemAnalysisReadScope(listOf("SDK"), null, null),
                ProblemAnalysisReadPeriod(null, null),
                ProblemAnalysisReadPage(1, 0),
                ProblemAnalysisReadPage(1, 20)
            ))
        }
        every { members.findClusterSummary(7, any(), 0) } returns cluster(0)
        val batch = assertThrows(ClientException::class.java) {
            repository.detail(ClusterDetailReadRequest(
                7,
                ProblemAnalysisReadScope(listOf("SDK"), null, null),
                0,
                ProblemAnalysisReadPeriod(null, null)
            ))
        }

        assertEquals(ErrorCode.BAD_REQUEST, pagination.errorCode)
        assertEquals("PROBLEM_ANALYSIS_PAGINATION_INVALID", pagination.reasonCode)
        assertEquals(ErrorCode.BAD_REQUEST, batch.errorCode)
        assertEquals("FEEDBACK_ANALYSIS_ID_BATCH_INVALID", batch.reasonCode)
    }

    @Test
    fun `missing cluster facts uses the shared unavailable contract`() {
        every { members.totals(7, any()) } returns ProblemTotalsPo(1, 0)
        every { members.findClusterPage(7, any(), false, 20, 0) } returns listOf(cluster(1))
        every { members.findClusterPage(7, any(), true, 20, 0) } returns emptyList()
        every { clusters.findByIds(listOf(1L)) } returns emptyList()

        val error = assertThrows(ServiceException::class.java) {
            repository.read(defaultRequest())
        }

        assertEquals(ErrorCode.FEEDBACK_READ_UNAVAILABLE, error.errorCode)
        assertEquals("FEEDBACK_CLUSTER_FACTS_INCOMPLETE", error.reasonCode)
    }

    @Test
    fun `missing feedback projection uses the shared unavailable contract`() {
        every { members.countClusterFeedback(7, any(), 42) } returns 1
        every { members.findClusterFeedbackPage(7, any(), 42, 20, 0) } returns listOf(
            com.feedback.model.po.feedback.listprojection.FeedbackClusterMemberProjectionPo(
                sourceId = 101,
                clusterId = 42,
                sourceChannel = "SDK"
            )
        )
        every { projections.findByIds(7, listOf(101L)) } returns emptyList()

        val error = assertThrows(ServiceException::class.java) {
            repository.feedback(
                ClusterFeedbackReadRequest(
                    7,
                    ProblemAnalysisReadScope(listOf("SDK"), null, null),
                    42,
                    ProblemAnalysisReadPage(1, 20),
                    ProblemAnalysisReadPeriod(null, null)
                )
            )
        }

        assertEquals(ErrorCode.FEEDBACK_READ_UNAVAILABLE, error.errorCode)
        assertEquals("FEEDBACK_CLUSTER_FEEDBACK_PROJECTION_INCOMPLETE", error.reasonCode)
    }

    private fun defaultRequest() = ProblemAnalysisReadRequest(
        7,
        ProblemAnalysisReadScope(listOf("SDK"), null, null),
        ProblemAnalysisReadPeriod(null, null),
        ProblemAnalysisReadPage(1, 20),
        ProblemAnalysisReadPage(1, 20)
    )

    private fun criteria() = com.feedback.model.query.problem.analysis.AnalysisCriteria(
        listOf("SDK"), null, null, null, null
    )

    private fun cluster(id: Long) = ProblemClusterPo(clusterId = id, recordCount = id * 10)
}
