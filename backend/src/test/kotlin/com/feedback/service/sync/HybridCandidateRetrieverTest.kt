package com.feedback.service.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HybridCandidateRetrieverTest {

    @Test
    fun `fusion promotes candidates returned by both engines`() {
        val result = retriever().retrieve("AI辅导闪退", 3)
        assertEquals(listOf(20L, 10L, 30L), result.map { it.sourceId })
        assertEquals(1, result.first().vectorRank)
        assertEquals(2, result.first().textRank)
    }

    // ---------------------------------------------------------------------------------------
    // 重排插在融合之后、截断之前
    // ---------------------------------------------------------------------------------------

    @Test
    fun `注入的重排器改变顺序但不改变条数`() {
        val result = retriever(Reranker { _, candidates -> candidates.reversed() }).retrieve("AI辅导闪退", 3)

        assertEquals(listOf(30L, 10L, 20L), result.map { it.sourceId })
        assertEquals(3, result.size, "重排不该改变返回条数")
    }

    /**
     * 重排器越权删元素时，**本类**必须把它拦住。
     *
     * 这里刻意手工构造而不是走 Spring：本类在构造函数里套 [GuardedReranker]，
     * 所以「装配者忘了套守卫」不是一种可能的状态。若哪天有人把守卫移到装配层，
     * 这条用例会变红——而它保护的正是 `derivedRecallLimitHit` 的判断依据。
     */
    @Test
    fun `重排器删元素时被守卫拦住，返回条数不变`() {
        val result = retriever(Reranker { _, candidates -> candidates.drop(1) }).retrieve("AI辅导闪退", 3)

        assertEquals(listOf(20L, 10L, 30L), result.map { it.sourceId })
        assertEquals(3, result.size)
    }

    @Test
    fun `重排器抛异常时退回融合顺序`() {
        val result = retriever(Reranker { _, _ -> throw IllegalStateException("模型挂了") }).retrieve("AI辅导闪退", 3)

        assertEquals(listOf(20L, 10L, 30L), result.map { it.sourceId })
    }

    /**
     * 重排插在**截断之前**：一条排在融合窗口末尾、本来会被 `take(topK)` 截掉的候选，
     * 可以被提到截断线以内。
     *
     * 这是重排唯一能带来的增益。若插在截断之后，它只能对一批已经定下来的候选换顺序，
     * 那么本节其余用例仍然会全绿，而这条会红——所以它是这一组里最关键的一条。
     */
    @Test
    fun `重排能把一条本来会被截掉的候选提到截断线以内`() {
        // 融合顺序是 [20, 10, 30]，topK=2 时 30 会被截掉。
        val result = retriever(Reranker { _, candidates ->
            candidates.filter { it.sourceId == 30L } + candidates.filter { it.sourceId != 30L }
        }).retrieve("AI辅导闪退", 2)

        assertEquals(listOf(30L, 20L), result.map { it.sourceId })
    }

    @Test
    fun `重排器拿到的是完整融合列表而不是截断后的窗口`() {
        var seen: List<Long>? = null
        retriever(Reranker { _, candidates -> seen = candidates.map { it.sourceId }; candidates })
            .retrieve("AI辅导闪退", 2)

        assertEquals(
            listOf(20L, 10L, 30L), seen,
            "传进去的是截断后的 2 条：那样重排永远只能重排已经定下来的候选，拿不到本方法唯一能提供的增益"
        )
    }

    @Test
    fun `重排器收到的查询就是调用方传的原文`() {
        var query: String? = null
        retriever(Reranker { text, candidates -> query = text; candidates }).retrieve("AI辅导闪退", 3)

        assertEquals("AI辅导闪退", query, "查询被截断或归一过：重排前后差在哪就无法归因到一个原因上")
    }

    @Test
    fun `重排器拿到的是融合分降序的列表`() {
        var scores: List<Double>? = null
        retriever(Reranker { _, candidates -> scores = candidates.map { it.fusedScore }; candidates })
            .retrieve("AI辅导闪退", 3)

        val seen = scores!!
        assertTrue(seen.zipWithNext().all { (left, right) -> left >= right }, "传给重排器的不是融合顺序：$seen")
    }

    // ---------------------------------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------------------------------

    private val search = object : SearchIndexGateway {
        override fun upsert(document: SearchDocument) = document.sourceId.toString()
        override fun delete(sourceId: Long) = Unit
        override fun search(query: String, topK: Int, sourceIds: Set<Long>, excludeSourceId: Long?) =
            listOf(SearchCandidate(10, 8.0), SearchCandidate(20, 4.0))
    }

    private val vectors = object : VectorIndexGateway {
        override fun upsert(sourceId: Long, vector: List<Float>) = sourceId.toString()
        override fun search(vector: List<Float>, topK: Int, filter: VectorSearchFilter) =
            listOf(VectorCandidate(20, 0.91f), VectorCandidate(30, 0.88f))
        override fun delete(sourceId: Long) = Unit
        override fun rebuild(vectors: Map<Long, List<Float>>) = Unit
    }

    private val embedding = object : EmbeddingProvider {
        override val modelVersion = "test"
        override val dimension = 2
        override fun embed(text: String) = listOf(1f, 0f)
    }

    /** 默认不传重排器，走位置参数的三参构造——这条路径必须与引入本端口之前完全一致。 */
    private fun retriever(reranker: Reranker = Reranker.NONE) =
        HybridCandidateRetriever(search, vectors, embedding, reranker)
}
