package com.feedback.service.cluster

import com.feedback.service.sync.BgeM3OnnxEmbeddingProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Locale
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

@EnabledIfEnvironmentVariable(named = "CLUSTER_BGE_TEST_ENABLED", matches = "true")
class BgeM3ClusterSemanticIntegrationTest {
    @Test
    @Timeout(300)
    fun `本地 BGE M3 模型完整且同义症状排序正确`() {
        val modelPath = System.getenv("BGE_M3_PATH") ?: error("BGE_M3_PATH is required")
        val elapsed = measureTimeMillis {
            BgeM3OnnxEmbeddingProvider(modelPath, "onnx/model.onnx", 128).use { provider ->
                lateinit var query: List<Float>
                lateinit var similar: List<Float>
                lateinit var unrelated: List<Float>
                val queryMillis = measureTimeMillis { query = provider.embed("手写识别不准确") }
                val similarMillis = measureTimeMillis { similar = provider.embed("扫出来的不是我写的字") }
                val unrelatedMillis = measureTimeMillis { unrelated = provider.embed("视频播放时没有声音") }
                val similarScore = cosine(query, similar)
                val unrelatedScore = cosine(query, unrelated)

                assertEquals(1024, query.size)
                assertEquals(1.0, norm(query), 0.0001)
                assertTrue(similarScore > unrelatedScore, "$similarScore <= $unrelatedScore")
                println("CLUSTER_BGE_SMOKE similar=$similarScore unrelated=$unrelatedScore " +
                    "query_ms=$queryMillis similar_ms=$similarMillis unrelated_ms=$unrelatedMillis")
            }
        }
        println("CLUSTER_BGE_SMOKE elapsed_ms=$elapsed")
    }

    @Test
    @Timeout(300)
    fun `合成错误签名冲突金标准始终被硬边界拒绝`() {
        val rows = javaClass.classLoader.getResourceAsStream("cluster/signature-conflict-ground-truth-v1.tsv")
            ?.bufferedReader()?.useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("pair_id") }
                    .map { it.split('\t') }.toList()
            } ?: error("CLUSTER_SIGNATURE_GROUND_TRUTH_MISSING")
        assertEquals(8, rows.size)
        val extractor = AtomicProblemExtractorV3()
        val pairs = rows.mapIndexed { index, columns ->
            assertEquals(4, columns.size)
            assertEquals("DIFFERENT_ERROR_SIGNATURE", columns[3])
            val left = extractor.extract(input(index * 2L + 1, columns[1]))
            val right = extractor.extract(input(index * 2L + 2, columns[2]))
            assertEquals(ProblemClassificationStatus.ACTIONABLE, left.status)
            assertEquals(ProblemClassificationStatus.ACTIONABLE, right.status)
            assertEquals(left.failureMode, right.failureMode)
            assertEquals(left.target, right.target)
            assertEquals(left.action, right.action)
            assertTrue(left.errorSignature != null && right.errorSignature != null)
            assertTrue(left.errorSignature != right.errorSignature)
            assertEquals(BoundaryEvidence.CONFLICT, ProblemBoundaryPolicyV2().compare(left, right))
            left to right
        }
        val modelPath = System.getenv("BGE_M3_PATH") ?: error("BGE_M3_PATH is required")
        val scores = BgeM3OnnxEmbeddingProvider(modelPath, "onnx/model.onnx", 128).use { provider ->
            pairs.map { (left, right) -> cosine(provider.embed(semanticText(left)), provider.embed(semanticText(right))) }
        }.sorted()
        println("CLUSTER_BGE_SIGNATURE_GROUND_TRUTH pairs=${scores.size} min=${format(scores.first())} " +
            "p50=${format(scores[scores.size / 2])} max=${format(scores.last())} boundary_false_merges=0")
    }

    private fun input(sourceId: Long, text: String) = ClusterClassificationInput(
        sourceId = sourceId,
        applicationKey = "signature-fixture-app",
        applicationName = "签名边界夹具",
        title = null,
        description = text
    )

    private fun semanticText(profile: AtomicProblemProfile) = ClusterSemanticTextV3.of(profile)

    private fun format(value: Double) = String.format(Locale.ROOT, "%.4f", value)

    private fun norm(vector: List<Float>) = sqrt(vector.sumOf { (it * it).toDouble() })
    private fun cosine(left: List<Float>, right: List<Float>) =
        left.indices.sumOf { (left[it] * right[it]).toDouble() }
}

