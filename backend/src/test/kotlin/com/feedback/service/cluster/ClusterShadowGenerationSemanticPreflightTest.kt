package com.feedback.service.cluster

import com.feedback.common.exception.ServiceException
import com.feedback.repository.cluster.ClusterShadowGenerationRepository
import com.feedback.service.sync.EmbeddingProvider
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ClusterShadowGenerationSemanticPreflightTest {
    @Test
    fun `BGE 运行探针失败时在创建 generation 前关闭`() {
        val repository = mockk<ClusterShadowGenerationRepository>(relaxed = true)
        val input = HistoricalClusterShadowInputPort { _, _ -> error("model gate must run before snapshot read") }
        val broken = object : EmbeddingProvider {
            override val modelVersion = "BAAI/bge-m3-onnx-dense-v1"
            override val dimension = 1024
            override fun embed(text: String): List<Float> = error("unavailable")
        }
        val service = ClusterShadowGenerationService(repository, input, broken)

        val error = assertThrows(ServiceException::class.java) {
            service.build(ClusterShadowGenerationCommand("shadow-11-full-20260909-post-identity", 9))
        }

        assertEquals("CLUSTER_SHADOW_GENERATION_BGE_M3_RUNTIME_FAILED", error.reasonCode)
        verify(exactly = 0) { repository.createBuilding(any()) }
        verify(exactly = 0) { repository.markFailed(any(), any(), any()) }
    }
}
