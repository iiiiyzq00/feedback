package com.feedback.service.cluster

import com.feedback.service.ai.PromptAssetBootstrap
import com.feedback.service.normalization.FaultOccurrenceTimeBackfillRunner
import com.feedback.service.sync.BgeM3OnnxEmbeddingProvider
import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.Locale

/** 扩大只读窗口寻找真实错误签名冲突；只输出数量和分数分布，不输出正文、签名或可反查 ID。 */
@EnabledIfEnvironmentVariable(named = "CLUSTER_SIGNATURE_AUDIT_ENABLED", matches = "true")
@SpringBootTest(properties = [
    "app.cluster.reclassification-worker-enabled=false",
    "app.jira.assignee-owner-backfill-enabled=false",
    "xxl.job.enabled=false",
    "spring.flyway.enabled=false",
    "spring.datasource.hikari.read-only=true",
    "spring.main.lazy-initialization=true"
])
class BgeM3HistoricalErrorSignatureAuditTest @Autowired constructor(
    private val input: HistoricalClusterShadowInputPort
) {
    @MockkBean(relaxed = true)
    private lateinit var faultOccurrenceBackfill: FaultOccurrenceTimeBackfillRunner

    @MockkBean(relaxed = true)
    private lateinit var promptAssetBootstrap: PromptAssetBootstrap

    @Test
    @Timeout(900)
    fun `扩大真实历史窗口审计错误签名冲突`() {
        val records = readBatches(MAX_RECORDS)
        val profiles = records.map { AtomicProblemExtractorV3().extract(it.classificationInput) }
            .filter { it.status == ProblemClassificationStatus.ACTIONABLE }
            .sortedBy(AtomicProblemProfile::sourceId)
        val conflicts = buildList {
            for (leftIndex in profiles.indices) for (rightIndex in leftIndex + 1 until profiles.size) {
                val left = profiles[leftIndex]
                val right = profiles[rightIndex]
                if (left.applicationKey == right.applicationKey && left.failureMode == right.failureMode &&
                    left.errorSignature != null && right.errorSignature != null &&
                    left.errorSignature != right.errorSignature) add(left to right)
            }
        }
        val scores = if (conflicts.isEmpty()) emptyList() else {
            val involved = conflicts.flatMap { listOf(it.first, it.second) }.distinctBy(AtomicProblemProfile::sourceId)
            val modelPath = System.getenv("BGE_M3_PATH") ?: error("BGE_M3_PATH is required")
            BgeM3OnnxEmbeddingProvider(modelPath, "onnx/model.onnx", 128).use { provider ->
                val vectors = involved.associate { it.sourceId to provider.embed(semanticText(it)) }
                conflicts.map { (left, right) -> cosine(vectors.getValue(left.sourceId), vectors.getValue(right.sourceId)) }
            }
        }
        println("CLUSTER_SIGNATURE_AUDIT records=${records.size} actionable=${profiles.size} " +
            "signature_conflicts=${conflicts.size} ${summary(scores)}")
        assertEquals(conflicts.size, conflicts.count { (left, right) ->
            ProblemBoundaryPolicyV2().compare(left, right) == BoundaryEvidence.CONFLICT
        })
    }

    private fun readBatches(limit: Int): List<HistoricalClusterShadowInput> {
        val result = mutableListOf<HistoricalClusterShadowInput>()
        var cursor = 0L
        while (result.size < limit) {
            val batch = input.read(cursor, minOf(HistoricalClusterShadowInputReader.MAX_BATCH_SIZE, limit - result.size))
            result += batch.records
            if (!batch.mayHaveMore || batch.nextSourceId <= cursor) break
            cursor = batch.nextSourceId
        }
        return result
    }

    private fun semanticText(profile: AtomicProblemProfile) = ClusterSemanticTextV3.of(profile)

    private fun cosine(left: List<Float>, right: List<Float>) =
        left.indices.sumOf { (left[it] * right[it]).toDouble() }.coerceIn(0.0, 1.0)

    private fun summary(scores: List<Double>): String = if (scores.isEmpty()) "score_count=0" else {
        val sorted = scores.sorted()
        "score_count=${sorted.size} min=${format(sorted.first())} p50=${format(sorted[sorted.size / 2])} max=${format(sorted.last())}"
    }

    private fun format(value: Double) = String.format(Locale.ROOT, "%.4f", value)

    companion object { private const val MAX_RECORDS = 2_000 }
}
