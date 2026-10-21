package com.feedback.repository.problem.analysis

import com.feedback.application.port.problem.analysis.*
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.problem.analysis.ClusterFeedbackProjectionMapper
import com.feedback.mapper.problem.analysis.ProblemAnalysisMemberMapper
import com.feedback.model.po.problem.analysis.ClusterFeedbackProjectionPo
import com.feedback.model.po.feedback.listprojection.FeedbackClusterMemberProjectionPo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** 验证同簇读取保持一次分页、一次批量正文读取，并在安全投影缺失时整体失败。 */
class RelatedFeedbackRepositoryTest {
    private val members = mockk<ProblemAnalysisMemberMapper>()
    private val projections = mockk<ClusterFeedbackProjectionMapper>()
    private val repository = RelatedFeedbackRepository(members, projections)
    private val request = RelatedFeedbackReadRequest(
        7,
        RelatedFeedbackScope(listOf("SDK"), listOf("COURSE"), null),
        11,
        1,
        10
    )

    @Test
    fun `page members are enriched by one bounded projection query`() {
        every { members.findMemberBySourceId(7, any(), 11) } returns member(11)
        every { members.countRelatedFeedback(7, any(), 42, 11) } returns 1
        every { members.findRelatedFeedbackPage(7, any(), 42, 11, 10, 0) } returns listOf(member(13))
        every { projections.findByIds(7, listOf(13L)) } returns listOf(
            ClusterFeedbackProjectionPo(sourceId = 13, description = "安全正文")
        )

        val result = repository.read(request)

        assertEquals(1, result.total)
        assertEquals("安全正文", result.items.single().description)
        verify(exactly = 1) { members.findRelatedFeedbackPage(7, any(), 42, 11, 10, 0) }
        verify(exactly = 1) { projections.findByIds(7, listOf(13L)) }
    }

    @Test
    fun `missing safe projection rejects the whole page`() {
        every { members.findMemberBySourceId(7, any(), 11) } returns member(11)
        every { members.countRelatedFeedback(7, any(), 42, 11) } returns 1
        every { members.findRelatedFeedbackPage(7, any(), 42, 11, 10, 0) } returns listOf(member(13))
        every { projections.findByIds(7, listOf(13L)) } returns emptyList()

        val error = assertThrows(ServiceException::class.java) { repository.read(request) }

        assertEquals(ErrorCode.FEEDBACK_READ_UNAVAILABLE, error.errorCode)
        assertEquals("FEEDBACK_RELATED_FEEDBACK_PROJECTION_INCOMPLETE", error.reasonCode)
    }

    @Test
    fun `invalid pagination uses the shared client exception contract`() {
        val error = assertThrows(ClientException::class.java) {
            repository.read(request.copy(page = 0))
        }

        assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
        assertEquals("RELATED_FEEDBACK_PAGINATION_INVALID", error.reasonCode)
    }

    private fun member(sourceId: Long) = FeedbackClusterMemberProjectionPo(
        sourceId = sourceId,
        clusterId = 42,
        sourceChannel = "SDK"
    )
}
