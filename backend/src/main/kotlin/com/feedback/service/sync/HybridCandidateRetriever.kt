package com.feedback.service.sync

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import org.springframework.stereotype.Service

data class HybridCandidate(
    val sourceId: Long,
    val fusedScore: Double,
    val textRank: Int?,
    val vectorRank: Int?,
    val vectorScore: Float?
)

/**
 * 只负责候选召回；最终判定仍由 MySQL 过滤与强证据规则做出。
 *
 * ## 两个阶段，边界是刻意划的
 *
 * 1. **融合**（本类）：两个引擎的名次 → `1/(K+rank)` 求和 → 按融合分降序、同分按
 *    `sourceId` 升序。这一段没有任何外部依赖，同一份输入永远得到同一个输出。
 * 2. **重排**（[Reranker]，默认 [Reranker.NONE]）：融合之后的可选一道。
 *
 * ## 为什么注入的是「已经套了守卫的」重排器
 *
 * [GuardedReranker] 在构造时就被套上，而不是由调用方决定要不要套。理由是两条约束的分工：
 * 「这个实现值不值得信」是实现自己的事，「集合不许被改」是**本类的输出契约**——
 * 后者不该取决于谁装配了本类。测试里注入一个会乱来的重排器，也应当被同一层拦住。
 *
 * ## 默认值是恒等重排，不是「默认关闭的开关」
 *
 * `reranker` 的默认值是 [Reranker.NONE]，于是没有 Spring、只按位置传三个参数构造
 * 本类的地方（`HybridCandidateRetrieverTest`、`HybridRecallIntegrationTest`）
 * 行为逐字节不变。生产装配时 Spring 会注入真正的实现，
 * 但它内部还有一个默认关闭的开关（见 `LlmCandidateReranker`）——
 * **两道闸门是分开的**：没有重排实现与有实现但没打开，在日志里必须能分辨。
 */
@Service
class HybridCandidateRetriever(
    private val searchIndex: SearchIndexGateway,
    private val vectorIndex: VectorIndexGateway,
    private val embeddings: EmbeddingProvider,
    reranker: Reranker = Reranker.NONE
) {
    private val reranker: Reranker = GuardedReranker(reranker)

    fun retrieve(text: String, topK: Int = 20, sourceIds: Set<Long> = emptySet(), excludeSourceId: Long? = null): List<HybridCandidate> {
        if(text.isBlank()) invalid("CANDIDATE_QUERY_EMPTY")
        if(topK !in 1..100) invalid("CANDIDATE_TOP_K_INVALID")
        val fetchSize = (topK * 2).coerceAtMost(100)
        val textCandidates = searchIndex.search(text, fetchSize, sourceIds, excludeSourceId)
        val vectorCandidates = vectorIndex.search(embeddings.embed(text), fetchSize, VectorSearchFilter(sourceIds, excludeSourceId))
        val textRanks = textCandidates.mapIndexed { index, value -> value.sourceId to index + 1 }.toMap()
        val vectorRanks = vectorCandidates.mapIndexed { index, value -> value.sourceId to index + 1 }.toMap()
        val vectorScores = vectorCandidates.associate { it.sourceId to it.score }
        val fused = (textRanks.keys + vectorRanks.keys).map { sourceId ->
            val textRank = textRanks[sourceId]
            val vectorRank = vectorRanks[sourceId]
            HybridCandidate(sourceId, listOfNotNull(textRank, vectorRank).sumOf { 1.0 / (RRF_K + it) },
                textRank, vectorRank, vectorScores[sourceId])
        }.sortedWith(compareByDescending<HybridCandidate> { it.fusedScore }.thenBy { it.sourceId })
        // 重排排在截断**之前**。放在之后的话，它只能重排一批已经定下来的候选，
        // 而重排唯一能带来的增益恰恰是「把一条本该被截掉的候选提上来」。
        // 返回条数不因此改变（重排只换顺序），所以调用方那套
        // 「返回条数 == 请求条数 → 召回被截断」的判断仍然成立。
        return reranker.rerank(text, fused).take(topK)
    }

    private fun invalid(reasonCode:String):Nothing = throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)

    companion object { private const val RRF_K = 60.0 }
}
