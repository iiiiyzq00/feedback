package com.feedback.service.cluster

import com.feedback.service.ai.LlmClient
import com.feedback.service.ai.ClusterAiAdvice
import com.feedback.model.cluster.Cluster
import com.feedback.repository.cluster.ClusterAiDecisionTask
import com.feedback.repository.cluster.ClusterAiTaskRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ClusterAiAssistedDecisionServiceTest {
    @Test
    fun `expired processing lease is atomically recovered before due work is selected`() {
        val tasks = mockk<ClusterAiTaskRepository>(relaxed = true)
        every { tasks.dueIds(10) } returns emptyList()
        val service = ClusterAiAssistedDecisionService(tasks, jacksonObjectMapper(), mockk(relaxed = true), true, 2)

        service.processDue()

        io.mockk.verify(exactly = 1) { tasks.recoverExpired() }
    }

    @Test
    fun `AI cannot link a deterministic new cluster to the wrong candidate`() {
        val candidate = CandidateEvidence(Cluster(id = 2, clusterName = "课程下载失败"), BigDecimal("0.900000"))
        val deterministic = ClusterDecisionPlan("NEW_CLUSTER", null, listOf(candidate), "播放和下载边界不同")
        val resolution = ClusterAiResolution(ClusterAiResolutionStatus.COMPLETED, advice("LINK_EXISTING", "cluster:2"))

        assertEquals("NEW_CLUSTER", applyAiPermissionModel(deterministic, resolution, BigDecimal("0.85")).action)
    }

    @Test
    fun `AI cannot move a deterministic link to a competing cluster`() {
        val selected = CandidateEvidence(Cluster(id = 1, clusterName = "课程播放失败"), BigDecimal.ONE)
        val deterministic = ClusterDecisionPlan("AUTO_HIGH", selected, listOf(selected), "确定性一致")

        val result = applyAiPermissionModel(
            deterministic,
            ClusterAiResolution(ClusterAiResolutionStatus.COMPLETED, advice("LINK_EXISTING", "cluster:2")),
            BigDecimal("0.85")
        )

        assertEquals(1L, result.selected?.cluster?.id)
        assertEquals("AUTO_HIGH", result.action)
    }

    @Test
    fun `failure update compares the incremented retry count without adding twice`() {
        val tasks = mockk<ClusterAiTaskRepository>()
        every { tasks.claim(7L, any()) } returns true
        every { tasks.decision(7L) } returns ClusterAiDecisionTask(7L, "PROCESSING", "{}", null)
        every { tasks.fail(7L, any(), any()) } returns Unit
        val llm = mockk<LlmClient>()
        every { llm.adviseCluster(any()) } throws IllegalStateException("provider unavailable")

        ClusterAiAssistedDecisionService(tasks, jacksonObjectMapper(), llm, true, 3).process(7L)

        io.mockk.verify(exactly = 1) { tasks.fail(7L, any(), match { it.contains("CLUSTER_AI_DECISION") }) }
    }

    @Test
    fun `malformed completed advice falls back instead of blocking clustering`() {
        val tasks = mockk<ClusterAiTaskRepository>()
        every { tasks.decision("CLUSTER_AI_DECISION:7") } returns ClusterAiDecisionTask(7L, "COMPLETED", null, "{")
        val service = ClusterAiAssistedDecisionService(
            tasks, jacksonObjectMapper(), mockk(relaxed = true), true, 1
        )

        val result = service.resolveOrEnqueue(7L, mockk(), mockk(), emptyList())

        assertEquals(ClusterAiResolutionStatus.FAILED, result.status)
    }

    @Test
    fun `cancelled advice task falls back instead of remaining pending`() {
        val tasks = mockk<ClusterAiTaskRepository>()
        every { tasks.decision("CLUSTER_AI_DECISION:7") } returns ClusterAiDecisionTask(7L, "CANCELLED", null, null)
        val service = ClusterAiAssistedDecisionService(
            tasks, jacksonObjectMapper(), mockk(relaxed = true), true, 1
        )

        val result = service.resolveOrEnqueue(7L, mockk(), mockk(), emptyList())

        assertEquals(ClusterAiResolutionStatus.FAILED, result.status)
    }

    @Test
    fun `completed advice with a generic cluster phenomenon is rejected`() {
        val tasks = mockk<ClusterAiTaskRepository>()
        every { tasks.decision("CLUSTER_AI_DECISION:7") } returns ClusterAiDecisionTask(7L, "COMPLETED", null, """{
                "recommendedAction":"CREATE_NEW",
                "selectedClusterRef":null,
                "canonicalSymptom":"应用程序",
                "explanation":"名称过宽",
                "evidenceRefs":["source:7"],
                "riskSignals":[],
                "confidence":0.99
            }""".trimIndent())
        val service = ClusterAiAssistedDecisionService(
            tasks, jacksonObjectMapper(), mockk(relaxed = true), true, 1
        )

        val result = service.resolveOrEnqueue(7L, mockk(), mockk(), emptyList())

        assertEquals(ClusterAiResolutionStatus.FAILED, result.status)
    }

    private fun advice(action: String, selected: String?) = ClusterAiAdvice(
        action, selected, "课程播放失败", "测试错误建议", listOf("source:7", selected).filterNotNull(),
        emptyList(), BigDecimal("0.99")
    )
}
