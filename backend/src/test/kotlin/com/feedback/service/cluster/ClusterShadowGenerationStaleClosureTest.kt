package com.feedback.service.cluster

import com.feedback.model.po.cluster.ClusterShadowGenerationPo
import com.feedback.repository.cluster.ClusterShadowGenerationRepository
import com.feedback.service.sync.EmbeddingProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ClusterShadowGenerationStaleClosureTest {
    @Test
    fun `过期槽位只转 FAILED 且不删除候选`() {
        val repository = mockk<ClusterShadowGenerationRepository>()
        val building = ClusterShadowGenerationPo(
            id = 41,
            generationKey = ClusterShadowGenerationService.STALE_PRE_GOVERNANCE_GENERATION,
            generationStatus = "BUILDING",
            processedCount = 15_189,
            expectedSourceCount = 15_189
        )
        val failed = building.copy(
            generationStatus = "FAILED",
            failureCode = "CLUSTER_SHADOW_GENERATION_SUPERSEDED"
        )
        every { repository.findByKey(building.generationKey) } returnsMany listOf(building, failed)
        every { repository.markFailed(building.generationKey, any(), any()) } returns Unit
        val service = ClusterShadowGenerationService(
            repository,
            mockk<HistoricalClusterShadowInputPort>(),
            mockk<EmbeddingProvider>()
        )

        val result = service.abandonStale(ClusterShadowGenerationCommand(building.generationKey, 9))

        assertEquals("FAILED", result.generationStatus)
        verify(exactly = 1) {
            repository.markFailed(
                building.generationKey,
                "CLUSTER_SHADOW_GENERATION_SUPERSEDED",
                "Pre-governance shadow generation superseded; candidate evidence retained"
            )
        }
        verify(exactly = 0) { repository.members(any()) }
        verify(exactly = 0) { repository.clusters(any()) }
    }
}
