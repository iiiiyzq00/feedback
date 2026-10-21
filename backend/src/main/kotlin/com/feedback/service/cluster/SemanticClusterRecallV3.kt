package com.feedback.service.cluster

import com.feedback.service.sync.EmbeddingProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.Callable
import java.util.concurrent.ArrayBlockingQueue
import java.util.Collections
import java.util.concurrent.Future
import java.util.LinkedHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** 可选的语义能力。实现可以是本地模型，也可以是远端服务。 */
fun interface SemanticEmbeddingPort {
    fun embed(text: String): List<Float>
}

/** 复用已有的向量化提供方，同时不让聚类算法依赖 Milvus。 */
class EmbeddingProviderSemanticPort(private val delegate: EmbeddingProvider) : SemanticEmbeddingPort {
    override fun embed(text: String): List<Float> = delegate.embed(text)
}

/** 只保留用户症状语义；SDK 尾随 JSON、轨迹附件和埋点标识不得进入向量或缓存键。 */
object ClusterSemanticTextV3 {
    fun of(profile: AtomicProblemProfile): String = listOfNotNull(
        sanitizeClause(profile.primaryClause),
        profile.canonicalSymptom,
        profile.target,
        profile.action,
        profile.failureMode,
        profile.capabilityDomain?.label,
        profile.faultFamily?.label
    ).filter(String::isNotBlank).joinToString(" | ").take(2_000)

    fun sanitizeClause(value: String?): String = value.orEmpty()
        .replace(TRACE_ATTACHMENT, " ")
        .replace(TECHNICAL_METADATA_SUFFIX, " ")
        .replace(EVENT_CODE_SUFFIX, " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val TRACE_ATTACHMENT = Regex("(?is)\\s*书写轨迹\\s*:[^\\s]+")
    private val TECHNICAL_METADATA_SUFFIX = Regex("(?is)\\s*\\{.*$")
    private val EVENT_CODE_SUFFIX = Regex("(?is)\\s*埋点\\s*code\\s*:\\s*[^\\s]+.*$")
}

data class SemanticRecallPolicyV3(
    val acceptThreshold: BigDecimal = BigDecimal("0.820000"),
    val provisionalThreshold: BigDecimal = BigDecimal("0.680000"),
    // 后校准对抗集已有 4 个 accept 区间兼容负例，但它们参与过硬边界开发，尚不能替代独立冻结验证；取 0.20 保守限制弱证据增益。
    val semanticWeight: BigDecimal = BigDecimal("0.200000"),
    val totalTimeoutMillis: Long = 3_000,
    val maxSemanticCandidates: Int = 50
) {
    init {
        ensureClusterInvariant(acceptThreshold in BigDecimal.ZERO..BigDecimal.ONE) {
            "CLUSTER_SEMANTIC_ACCEPT_THRESHOLD_INVALID"
        }
        ensureClusterInvariant(provisionalThreshold in BigDecimal.ZERO..acceptThreshold) {
            "CLUSTER_SEMANTIC_PROVISIONAL_THRESHOLD_INVALID"
        }
        ensureClusterInvariant(semanticWeight in BigDecimal.ZERO..BigDecimal.ONE) {
            "CLUSTER_SEMANTIC_WEIGHT_INVALID"
        }
        ensureClusterInvariant(totalTimeoutMillis in 1..30_000) {
            "CLUSTER_SEMANTIC_TIMEOUT_INVALID"
        }
        ensureClusterInvariant(maxSemanticCandidates in 1..200) {
            "CLUSTER_SEMANTIC_CANDIDATE_LIMIT_INVALID"
        }
    }
}

enum class SemanticAcceptEligibilityReason {
    ELIGIBLE,
    BROAD_FAILURE_MODE,
    TARGET_ONE_SIDED_MISSING,
    ACTION_ONE_SIDED_MISSING,
    ERROR_SIGNATURE_ONE_SIDED_MISSING
}

/** 高余弦只在症状类型足够具体且可选硬边界没有单侧缺失时获得自动接受资格。 */
object SemanticAutoAcceptEligibilityV4 {
    private val broadFailureModes = setOf("CONTENT_ERROR", "WRITING_TRAJECTORY_ERROR", "EXPLANATION_ERROR")

    fun reason(left: AtomicProblemProfile, right: AtomicProblemProfile): SemanticAcceptEligibilityReason = when {
        left.failureMode in broadFailureModes || right.failureMode in broadFailureModes ->
            SemanticAcceptEligibilityReason.BROAD_FAILURE_MODE
        oneSidedMissing(left.target, right.target) -> SemanticAcceptEligibilityReason.TARGET_ONE_SIDED_MISSING
        oneSidedMissing(left.action, right.action) -> SemanticAcceptEligibilityReason.ACTION_ONE_SIDED_MISSING
        oneSidedMissing(left.errorSignature, right.errorSignature) ->
            SemanticAcceptEligibilityReason.ERROR_SIGNATURE_ONE_SIDED_MISSING
        else -> SemanticAcceptEligibilityReason.ELIGIBLE
    }

    private fun oneSidedMissing(left: String?, right: String?) = (left == null) != (right == null)
}

/**
 * 语义打分只能细化那些已经满足确定性硬边界的候选。
 * 模型报错、超时、向量为空或维度不匹配，一律退回纯规则的精确召回。
 */
class SemanticCandidateRecallV3(
    private val embeddings: SemanticEmbeddingPort,
    private val deterministic: ShadowCandidateRecall = ClusterCandidateRecallV2(),
    private val boundary: ProblemBoundaryPolicyV2 = ProblemBoundaryPolicyV2(),
    private val policy: SemanticRecallPolicyV3 = SemanticRecallPolicyV3(),
    private val fallbackObserver: (SemanticRecallFallbackReason) -> Unit = {}
) : ShadowCandidateRecall {
    private val vectorCache = Collections.synchronizedMap(object : LinkedHashMap<String, List<Float>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Float>>?): Boolean =
            size > MAX_CACHE_ENTRIES
    })

    override fun recall(
        query: AtomicProblemProfile,
        candidates: Collection<AtomicProblemProfile>,
        topK: Int
    ): List<ShadowCandidate> {
        ensureClusterRequest(topK in 1..50) { "CLUSTER_SHADOW_TOP_K_OUT_OF_RANGE" }
        val fallback = deterministic.recall(query, candidates, topK)
        val hardCompatible = candidates.asSequence()
            .filter { it.sourceId != query.sourceId &&
                it.applicationPartitionKey() == query.applicationPartitionKey() }
            .map { it to boundary.compare(query, it) }
            .filter { it.second != BoundaryEvidence.CONFLICT }
            .sortedWith(compareBy<Pair<AtomicProblemProfile, BoundaryEvidence>> { it.first.identityKey }
                .thenBy { it.first.sourceId })
            .toList()
        val fallbackIds = fallback.mapTo(linkedSetOf()) { it.profile.sourceId }
        val eligible = (hardCompatible.filter { it.first.sourceId in fallbackIds } +
            hardCompatible.filterNot { it.first.sourceId in fallbackIds })
            .distinctBy { it.first.sourceId }
            .take(maxOf(policy.maxSemanticCandidates, fallback.size))
        if (eligible.isEmpty()) return fallback

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(policy.totalTimeoutMillis)
        val queryVector = embedSafely(ClusterSemanticTextV3.of(query), deadline) ?: return fallback.also {
            fallbackObserver(SemanticRecallFallbackReason.QUERY_EMBEDDING_UNAVAILABLE)
        }
        val enhanced = mutableListOf<ShadowCandidate>()
        for ((candidate, evidence) in eligible) {
            val vector = embedSafely(ClusterSemanticTextV3.of(candidate), deadline) ?: return fallback.also {
                fallbackObserver(SemanticRecallFallbackReason.CANDIDATE_EMBEDDING_UNAVAILABLE)
            }
            val semantic = cosine(queryVector, vector) ?: return fallback.also {
                fallbackObserver(SemanticRecallFallbackReason.SEMANTIC_SCORE_INVALID)
            }
            val eligibility = SemanticAutoAcceptEligibilityV4.reason(query, candidate)
            val judgment = when {
                evidence != BoundaryEvidence.MATCH -> SemanticCandidateJudgment.REJECT
                semantic >= policy.acceptThreshold && eligibility == SemanticAcceptEligibilityReason.ELIGIBLE ->
                    SemanticCandidateJudgment.ACCEPT
                semantic >= policy.provisionalThreshold -> SemanticCandidateJudgment.PROVISIONAL
                else -> SemanticCandidateJudgment.REJECT
            }
            val ruleScore = deterministic.similarity(query, candidate)
            val combined = if (judgment == SemanticCandidateJudgment.ACCEPT) {
                (ruleScore + semantic.multiply(policy.semanticWeight)).min(BigDecimal.ONE)
            } else ruleScore
            enhanced += ShadowCandidate(
                candidate,
                combined.setScale(6, RoundingMode.HALF_UP),
                evidence,
                semantic.setScale(6, RoundingMode.HALF_UP),
                judgment,
                eligibility
            )
        }
        return enhanced.sortedWith(compareByDescending<ShadowCandidate>(ShadowCandidate::score)
            .thenBy { it.profile.identityKey }.thenBy { it.profile.sourceId }).take(topK)
    }

    override fun similarity(left: AtomicProblemProfile, right: AtomicProblemProfile): BigDecimal {
        if (boundary.compare(left, right) != BoundaryEvidence.MATCH) return deterministic.similarity(left, right)
        if (SemanticAutoAcceptEligibilityV4.reason(left, right) != SemanticAcceptEligibilityReason.ELIGIBLE) {
            return deterministic.similarity(left, right)
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(policy.totalTimeoutMillis)
        val leftVector = embedSafely(ClusterSemanticTextV3.of(left), deadline) ?: return deterministic.similarity(left, right).also {
            fallbackObserver(SemanticRecallFallbackReason.SIMILARITY_EMBEDDING_UNAVAILABLE)
        }
        val rightVector = embedSafely(ClusterSemanticTextV3.of(right), deadline) ?: return deterministic.similarity(left, right).also {
            fallbackObserver(SemanticRecallFallbackReason.SIMILARITY_EMBEDDING_UNAVAILABLE)
        }
        val semantic = cosine(leftVector, rightVector) ?: return deterministic.similarity(left, right).also {
            fallbackObserver(SemanticRecallFallbackReason.SEMANTIC_SCORE_INVALID)
        }
        if (semantic < policy.acceptThreshold) return deterministic.similarity(left, right)
        return (deterministic.similarity(left, right) + semantic.multiply(policy.semanticWeight))
            .min(BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP)
    }

    private fun embedSafely(text: String, deadlineNanos: Long): List<Float>? {
        vectorCache[text]?.let { return it }
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) return null
        val future: Future<List<Float>> = try {
            SemanticEmbeddingExecutor.executor.submit(Callable { embeddings.embed(text) })
        } catch (_: Exception) {
            return null
        }
        return try {
            future.get(remaining, TimeUnit.NANOSECONDS).takeIf(::validVector)?.also { vectorCache[text] = it }
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }

    private fun validVector(vector: List<Float>): Boolean =
        vector.isNotEmpty() && vector.all { it.isFinite() } && vector.any { it != 0f }

    private fun cosine(left: List<Float>, right: List<Float>): BigDecimal? {
        if (left.size != right.size || !validVector(left) || !validVector(right)) return null
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return null
        return BigDecimal.valueOf((dot / sqrt(leftNorm * rightNorm)).coerceIn(0.0, 1.0))
    }

    companion object { private const val MAX_CACHE_ENTRIES = 5_000 }
}

enum class SemanticRecallFallbackReason {
    QUERY_EMBEDDING_UNAVAILABLE,
    CANDIDATE_EMBEDDING_UNAVAILABLE,
    SIMILARITY_EMBEDDING_UNAVAILABLE,
    SEMANTIC_SCORE_INVALID
}

private object SemanticEmbeddingExecutor {
    val executor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "cluster-semantic-embedding").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
}
