package com.feedback.repository.cluster

import com.feedback.common.exception.ServiceException
import com.feedback.mapper.cluster.ClusterShadowPreviewEvidenceMapper
import com.feedback.model.po.cluster.ClusterShadowCandidateClusterPo
import com.feedback.model.po.cluster.ClusterShadowCandidateMemberPo
import com.feedback.model.po.cluster.ClusterShadowGenerationPo
import com.feedback.model.po.cluster.ClusterShadowPreviewEvidencePo
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ClusterShadowPreviewRepositoryTest {
    private val candidates = mockk<ClusterShadowGenerationRepository>()
    private val evidence = mockk<ClusterShadowPreviewEvidenceMapper>()
    private val identities = mockk<ApplicationIdentityRepository>()
    private val repository = ClusterShadowPreviewRepository(candidates, evidence, identities)

    @Test
    fun `verified generation loads all actionable evidence with one bounded mapper call`() {
        every { candidates.findByKey("shadow-v1") } returns generation()
        every { candidates.clusters(7) } returns listOf(cluster())
        every { candidates.members(7) } returns listOf(actionable(), terminal())
        every { evidence.findByGeneration(7) } returns listOf(safeEvidence())
        every { identities.listAfter(null, 100) } returns emptyList()

        val result = repository.readVerified("shadow-v1")

        assertEquals(listOf(11L), result.members.map { it.feedbackSourceId })
        assertEquals("JIRA", result.evidenceBySourceId.getValue(11).channel)
        assertEquals("无法启动", result.evidenceBySourceId.getValue(11).title)
        assertEquals(mapOf("NOISE" to 1L), result.terminalCountByStatus)
        verify(exactly = 1) { evidence.findByGeneration(7) }
    }

    @Test
    fun `missing or unexpected evidence fails closed`() {
        every { candidates.findByKey("shadow-v1") } returns generation()
        every { candidates.clusters(7) } returns listOf(cluster())
        every { candidates.members(7) } returns listOf(actionable())
        every { evidence.findByGeneration(7) } returns emptyList()

        assertThrows(ServiceException::class.java) { repository.readVerified("shadow-v1") }

        verify(exactly = 0) { identities.listAfter(any(), any()) }
    }

    private fun generation() = ClusterShadowGenerationPo(
        id = 7,
        generationKey = "shadow-v1",
        generationStatus = "VERIFIED",
    )

    private fun cluster() = ClusterShadowCandidateClusterPo(7, "cluster-hash", "name-hash", 1, 0, 0)

    private fun actionable() = ClusterShadowCandidateMemberPo(
        generationId = 7,
        feedbackSourceId = 11,
        problemStatus = "ACTIONABLE",
        clusterKeyHash = "cluster-hash",
        decisionCode = "AUTO_ACCEPT",
        score = null,
        manualLocked = false,
        applicationHash = null,
        failureModeHash = null,
        targetHash = null,
        actionHash = null,
        errorSignatureHash = null,
    )

    private fun terminal() = actionable().copy(
        feedbackSourceId = 12,
        problemStatus = "NOISE",
        clusterKeyHash = null,
        decisionCode = "TERMINAL",
    )

    private fun safeEvidence() = ClusterShadowPreviewEvidencePo(
        sourceId = 11,
        sourceChannel = "JIRA",
        title = "无法启动",
        maskingStatus = "PASS",
        isDeleted = false,
    )
}
