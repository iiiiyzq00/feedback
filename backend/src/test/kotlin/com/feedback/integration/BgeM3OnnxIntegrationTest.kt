package com.feedback.integration

import com.feedback.service.sync.BgeM3OnnxEmbeddingProvider
import com.feedback.service.sync.MilvusGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.math.sqrt

@EnabledIfSystemProperty(named = "day4.bge.enabled", matches = "true")
class BgeM3OnnxIntegrationTest {
    @Test
    @Timeout(180)
    @EnabledIfSystemProperty(named = "day4.milvus.lite.enabled", matches = "true")
    fun `real bge vectors rank semantic candidate through milvus lite`() {
        val modelPath = System.getProperty("day4.bge.path")
            ?: error("day4.bge.path is required")
        BgeM3OnnxEmbeddingProvider(modelPath, "onnx/model.onnx", 128).use { provider ->
            MilvusGateway("127.0.0.1", 19530, "bge_m3_1024_verify", 1024).use { gateway ->
                gateway.rebuild(mapOf(
                    101L to provider.embed("AI辅导打开时应用崩溃"),
                    202L to provider.embed("视频播放过程中画面卡顿")
                ))
                val candidates = gateway.search(provider.embed("学习机AI辅导功能启动后闪退"), 2)
                assertEquals(2, candidates.size)
                assertEquals(101L, candidates.first().sourceId)
                assertTrue(candidates.first().score > candidates.last().score)
            }
        }
    }

    @Test
    @Timeout(180)
    fun `jvm onnx provider emits normalized semantic 1024 vectors`() {
        val modelPath = System.getProperty("day4.bge.path")
            ?: error("day4.bge.path is required")
        BgeM3OnnxEmbeddingProvider(modelPath, "onnx/model.onnx", 128).use { provider ->
            val query = provider.embed("学习机AI辅导功能启动后闪退")
            val similar = provider.embed("AI辅导打开时应用崩溃")
            val unrelated = provider.embed("视频播放过程中画面卡顿")

            assertEquals(1024, query.size)
            assertEquals(1.0, norm(query), 0.0001)
            assertTrue(cosine(query, similar) > cosine(query, unrelated))
        }
    }

    private fun norm(vector: List<Float>) = sqrt(vector.sumOf { (it * it).toDouble() })
    private fun cosine(left: List<Float>, right: List<Float>) =
        left.indices.sumOf { (left[it] * right[it]).toDouble() }
}
