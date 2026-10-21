package com.feedback.repository.cluster

import com.feedback.common.exception.ServiceException
import com.feedback.mapper.cluster.ClusterShadowPreviewEvidenceMapper
import com.feedback.model.po.cluster.ClusterShadowGenerationPo
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ClusterShadowPreviewRepositoryTest {
    private val candidates = mockk<ClusterShadowGenerationRepository>()
    private val previewEvidence = mockk<ClusterShadowPreviewEvidenceMapper>()
    private val identities = mockk<ApplicationIdentityRepository>()

    @Test
    fun `non verified generation fails before any member or content read`() {
        every { candidates.findByKey(any()) } returns ClusterShadowGenerationPo(
            id = 7,
            generationKey = "shadow-11-full-20260909-post-identity-r3",
            generationStatus = "BUILDING"
        )
        val repository = ClusterShadowPreviewRepository(candidates, previewEvidence, identities)

        val error = assertThrows(ServiceException::class.java) {
            repository.readVerified("shadow-11-full-20260909-post-identity-r3")
        }

        assertEquals("CLUSTER_SHADOW_PREVIEW_GENERATION_NOT_VERIFIED", error.reasonCode)
        verify(exactly = 0) { candidates.members(any()) }
        verify(exactly = 0) { candidates.clusters(any()) }
        verify(exactly = 0) { previewEvidence.findByGeneration(any()) }
    }
}
