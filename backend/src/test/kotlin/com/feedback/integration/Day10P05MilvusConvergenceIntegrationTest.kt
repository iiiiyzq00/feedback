package com.feedback.integration

import com.feedback.service.sync.BgeM3OnnxEmbeddingProvider
import com.feedback.service.sync.MilvusGateway
import com.feedback.service.sync.VectorSearchFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@EnabledIfSystemProperty(named = "day10.p05.enabled", matches = "true")
class Day10P05MilvusConvergenceIntegrationTest {
    @Test
    @Timeout(180)
    fun `isolated P05 range has the expected direct Milvus visibility`() {
        val first = System.getProperty("day10.p05.first").toLong()
        val last = System.getProperty("day10.p05.last").toLong()
        val expected = System.getProperty("day10.p05.expected", "present")
        val modelPath = System.getProperty("day4.bge.path")
        BgeM3OnnxEmbeddingProvider(modelPath, "onnx/model.onnx", 128).use { embeddings ->
            MilvusGateway("127.0.0.1", 19530, "feedback_vectors", 1024).use { gateway ->
                val ids = (first..last).toSet()
                val found = gateway.search(
                    embeddings.embed("D10P05_VECTOR_CAMERA 摄像头开启后课程崩溃"), 10,
                    VectorSearchFilter(sourceIds = ids)
                )
                if (expected == "present") {
                    assertEquals(10, found.size)
                    assertTrue(found.all { it.sourceId in ids })
                } else {
                    assertTrue(found.isEmpty(), "deleted P05 vectors remain visible: ${found.map { it.sourceId }}")
                }
            }
        }
    }
}
