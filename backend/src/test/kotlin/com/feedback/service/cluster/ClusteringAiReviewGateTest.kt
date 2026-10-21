package com.feedback.service.cluster

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.repository.cluster.ClusterMutationRepository
import com.feedback.repository.cluster.ClusterProfileFactRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClusteringAiReviewGateTest {
    @Test
    fun `scheduler selects every safe unclustered record without AI gate`() {
        val repository = mockk<ClusterMutationRepository>(relaxed = true)
        val facts = mockk<ClusterProfileFactRepository>()
        val engine = mockk<ClusteringEngine>(relaxed = true)
        every { repository.hasActiveReclassification() } returns false
        every { facts.eligibleAfter(0, ClusteringScheduler.BATCH_SIZE) } returns listOf(7L)
        every { repository.activeMainBySources(listOf(7L)) } returns emptyMap()

        ClusteringScheduler(repository, facts, engine).clusterReadyRecords()

        verify(exactly = 1) { repository.hasActiveReclassification() }
        verify(exactly = 1) { facts.eligibleAfter(0, 100) }
        verify(exactly = 1) { engine.cluster(7L) }
    }

    @Test
    fun `scheduler adds AI pending exclusion only when assisted decision is enabled`() {
        val repository = mockk<ClusterMutationRepository>(relaxed = true)
        val facts = mockk<ClusterProfileFactRepository>()
        val engine = mockk<ClusteringEngine>(relaxed = true)
        every { repository.hasActiveReclassification() } returns false
        every { facts.eligibleAfter(0, 100) } returns listOf(7L)
        every { repository.activeMainBySources(listOf(7L)) } returns emptyMap()
        every { repository.pendingAiSourceIds(listOf(7L)) } returns setOf(7L)

        ClusteringScheduler(repository, facts, engine, true).clusterReadyRecords()

        verify(exactly = 1) { repository.pendingAiSourceIds(listOf(7L)) }
        verify(exactly = 0) { engine.cluster(any()) }
    }

    @Test
    fun `engine source no longer rejects pending or missing AI result`() {
        val source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/kotlin/com/feedback/service/cluster/ClusteringEngine.kt"))
        assertFalse(source.contains("ai.reviewStatus !in AI_DOWNSTREAM_ELIGIBLE_STATUSES"))
        assertTrue(source.contains("FeedbackAi(sourceId = sourceId, reviewStatus = \"NOT_REQUIRED\")"))
    }

    @Test
    fun `engine blocks normal writes during reclassification but lets owned run proceed`() {
        val repository = mockk<ClusterMutationRepository>()
        every { repository.hasActiveReclassification() } returns true
        val engine = ClusteringEngine(
            repository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            jacksonObjectMapper(), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            null, null
        )

        assertTrue(engine.isNormalClusterWriteBlocked(null))
        assertFalse(engine.isNormalClusterWriteBlocked(7L))
        verify(exactly = 1) { repository.hasActiveReclassification() }
    }
}
