package com.feedback.service.cluster

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.config.RerankProperties
import com.feedback.service.ai.AiClassification
import com.feedback.service.ai.AiTransientException
import com.feedback.service.ai.LlmClient
import com.feedback.service.ai.LlmNarration
import com.feedback.service.sync.HybridCandidate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [LlmCandidateReranker] 的行为契约。
 *
 * ## 打桩 `LlmClient` 而不是接 MockWebServer
 *
 * `LlmInvestigationModelDegradationTest` 用 MockWebServer 是有理由的：它要验的是
 * 「异常到底长什么样」，三种出口在真实链路里的形态完全不同。本类要验的是
 * **拿到某个返回值之后做什么**——`decideStructured` 的返回值就是边界，
 * 边界另一侧怎么产生它不改变本类的任何判断。所以这里打桩，把注意力留给
 * 「半截顺序怎么补」「拿不到正文怎么办」这类本类自己的逻辑。
 *
 * ## 断言的重点是「没做什么」而不是「做了什么」
 *
 * 本类三条跳过路径的价值全在于**不产生调用**：不读正文、不发请求。
 * 所以断言里反复出现 `assertEquals(0, llm.calls)` 与 `assertNull(documents.loaded)`
 * ——只断言「顺序没变」的话，一个白白花了钱却没改顺序的实现同样会通过。
 *
 * ## 与 `GuardedRerankerTest` 的分工
 *
 * 本类不验「结果不是排列时怎么办」：那是守卫的职责，而生产装配里守卫一定在。
 * 本类只验**本类自己会产出什么**，以及在此之前它花了多少次调用。
 */
class LlmCandidateRerankerTest {

    private val json = ObjectMapper()

    // ---------------------------------------------------------------------------------------
    // 跳过路径一：开关关闭
    // ---------------------------------------------------------------------------------------

    @Test
    fun `开关关闭时不读正文不发请求，顺序逐字节不变`() {
        val llm = RecordingLlm { order(2, 1, 3, 4, 5) }
        val documents = RecordingDocuments(texts = mapOf(101L to "甲"))
        val reranker = reranker(llm, documents, RerankProperties().apply { enabled = false })

        val input = candidates(5)

        assertEquals(input.map { it.sourceId }, reranker.rerank("q", input).map { it.sourceId })
        assertEquals(0, llm.calls, "关闭状态下产生了模型调用")
        assertNull(documents.loaded, "关闭状态下读了候选正文")
    }

    @Test
    fun `候选不足两条时同样不产生调用`() {
        val llm = RecordingLlm { order(1) }
        val documents = RecordingDocuments(texts = mapOf(101L to "甲"))
        val reranker = reranker(llm, documents, enabledProperties())

        assertEquals(listOf(101L), reranker.rerank("q", candidates(1)).map { it.sourceId })
        assertEquals(0, llm.calls)
        assertNull(documents.loaded)
    }

    // ---------------------------------------------------------------------------------------
    // 跳过路径二：拿不到正文
    // ---------------------------------------------------------------------------------------

    /**
     * 这是本类最重要的一条断言。
     *
     * 拿不到正文多半意味着脱敏未 PASS（`loadShadowSafe` 在 SQL 层把非 PASS 行的文本全部空化）。
     * 这时把其余候选照常送出去排序，模型会在一个信息不全的集合上下判断，
     * 而它给出的顺序会被下游当成可信的。**整次跳过**是唯一诚实的处置。
     */
    @Test
    fun `有一条候选拿不到正文时整次跳过，一条都不发给模型`() {
        val llm = RecordingLlm { order(2, 1, 3) }
        val documents = RecordingDocuments(texts = mapOf(101L to "甲", 103L to "丙"))
        val reranker = reranker(llm, documents, enabledProperties())

        val input = candidates(3)

        assertEquals(input.map { it.sourceId }, reranker.rerank("q", input).map { it.sourceId })
        assertEquals(0, llm.calls, "集合信息不全却仍然发了请求：模型会在缺一条的集合上给出看起来完整的顺序")
        assertNotNull(documents.loaded, "应当先尝试读正文，再决定跳过")
    }

    /**
     * 「读到了行，但渲染出来是空的」与「没读到行」是**同一个分支**。
     *
     * 分两个用例是因为触发方式不同：前者是 `RerankDocument` 四个字段全空，
     * 后者是 Map 里根本没这个键。若哪天有人在 `render()` 里加一个占位串，
     * 这条用例会立刻变红。
     */
    @Test
    fun `正文存在但渲染为空时同样整次跳过`() {
        val llm = RecordingLlm { order(2, 1) }
        val documents = RecordingDocuments(
            texts = mapOf(101L to "甲"),
            blank = setOf(102L)
        )
        val reranker = reranker(llm, documents, enabledProperties())

        val input = candidates(2)

        assertEquals(input.map { it.sourceId }, reranker.rerank("q", input).map { it.sourceId })
        assertEquals(0, llm.calls)
    }

    @Test
    fun `正文读取抛异常时不穿透，且不产生模型调用`() {
        val llm = RecordingLlm { order(2, 1) }
        val documents = RecordingDocuments(texts = emptyMap(), failWith = IllegalStateException("库挂了"))
        val reranker = reranker(llm, documents, enabledProperties())

        val input = candidates(2)

        assertEquals(input.map { it.sourceId }, reranker.rerank("q", input).map { it.sourceId })
        assertEquals(0, llm.calls)
    }

    // ---------------------------------------------------------------------------------------
    // 跳过路径三：模型输出不可用
    // ---------------------------------------------------------------------------------------

    @Test
    fun `模型不可用时保持融合顺序且不抛异常`() {
        val llm = RecordingLlm { throw AiTransientException("LLM_NOT_CONFIGURED") }
        val reranker = reranker(llm, goodDocuments(), enabledProperties())

        val input = candidates(3)

        assertEquals(input.map { it.sourceId }, reranker.rerank("q", input).map { it.sourceId })
        assertEquals(1, llm.calls, "不可用是一条运行状态，不是本地短路——它必须真的尝试过")
    }

    @Test
    fun `模型抛未预期异常时同样不穿透`() {
        // 非 AiTransientException 意味着传输层之外出了事（例如解析代码有缺陷）。
        // 日志档位不同，但对调用方的契约相同：绝不因为重排让归簇失败。
        val llm = RecordingLlm { throw IllegalStateException("解析代码有缺陷") }
        val reranker = reranker(llm, goodDocuments(), enabledProperties())

        val input = candidates(3)

        assertEquals(input.map { it.sourceId }, reranker.rerank("q", input).map { it.sourceId })
    }

    /** 三种「读不懂」各一次，都是 HTTP 200：上游健康、响应可解析、只是语义不合约定。 */
    @Test
    fun `输出不含合法 order 时保持融合顺序`() {
        val payloads = listOf(
            null,                                        // structuredJson 为 null
            "",                                          // 空串
            "不是 JSON",                                  // 解析失败
            """{"ranking":[1,2,3]}""",                   // 字段名不对
            """{"order":"1,2,3"}""",                     // 类型不对
            """{"order":[]}""",                          // 空数组：一条「看起来成功」的日志
            """{"order":["1","2"]}"""                    // 元素不是整数
        )
        val input = candidates(3)

        payloads.forEach { payload ->
            val llm = RecordingLlm { LlmNarration(text = "x", structuredJson = payload) }
            val reranker = reranker(llm, goodDocuments(), enabledProperties())

            assertEquals(
                input.map { it.sourceId },
                reranker.rerank("q", input).map { it.sourceId },
                "payload=${payload} 时顺序被改动了：读不懂的输出来源不该改变任何顺序"
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // 生效路径
    // ---------------------------------------------------------------------------------------

    @Test
    fun `模型给出顺序时按它重排`() {
        val llm = RecordingLlm { order(3, 1, 2) }
        val reranker = reranker(llm, goodDocuments(), enabledProperties().apply { window = 3 })

        val input = candidates(3)

        assertEquals(listOf(103L, 101L, 102L), reranker.rerank("q", input).map { it.sourceId })
    }

    /**
     * 窗口之外的部分**保持融合顺序接在后面**，不可能被提到窗口内。
     *
     * 这条同时是成本口径的证明：窗口选多大，决定了重排最多能影响多少条。
     */
    @Test
    fun `只有窗口内的候选参与重排，尾部保持融合顺序`() {
        val llm = RecordingLlm { order(2, 1, 3) }
        val documents = goodDocuments()
        val reranker = reranker(llm, documents, enabledProperties().apply { window = 3 })

        val input = candidates(6)
        val result = reranker.rerank("q", input)

        assertEquals(listOf(102L, 101L, 103L, 104L, 105L, 106L), result.map { it.sourceId })
        assertEquals(listOf(101L, 102L, 103L), documents.loaded, "只该按窗口读正文：多读的正文既不进上下文也不该被查到")
    }

    /**
     * 模型给的编号越界或重复时，**被丢弃**，剩下的仍然生效，结果是入参的一个排列。
     *
     * `[0, 1, 1, 99, 3]` 里只有 1（→101）与 3（→103）能用：
     * 0 与 99 越界、第二个 1 重复。剩下的 102 保留窗口内原位置并排在已排的那些之后。
     */
    @Test
    fun `越界与重复的编号被丢弃，结果仍是入参的一个排列`() {
        val llm = RecordingLlm { LlmNarration(text = "x", structuredJson = """{"order":[0,1,1,99,3]}""") }
        val reranker = reranker(llm, goodDocuments(), enabledProperties().apply { window = 3 })

        val input = candidates(3)
        val result = reranker.rerank("q", input)

        assertEquals(listOf(101L, 103L, 102L), result.map { it.sourceId })
        assertEquals(input.map { it.sourceId }.sorted(), result.map { it.sourceId }.sorted(), "集合被改动了")
    }

    /** 模型只给了部分顺序时，没提到的候选不会被挪到最前或最后，而是保留窗口内的相对位置。 */
    @Test
    fun `模型只给部分顺序时，没提到的候选保留窗口内相对位置`() {
        val llm = RecordingLlm { LlmNarration(text = "x", structuredJson = """{"order":[2,1]}""") }
        val reranker = reranker(llm, goodDocuments(), enabledProperties().apply { window = 4 })

        val input = candidates(5)
        val result = reranker.rerank("q", input)

        assertEquals(listOf(102L, 101L, 103L, 104L, 105L), result.map { it.sourceId })
        assertEquals(5, result.size)
    }

    // ---------------------------------------------------------------------------------------
    // 发给模型的东西
    // ---------------------------------------------------------------------------------------

    /**
     * 请求体里只有 position，没有 sourceId。
     *
     * 让模型回抄一串 19 位数字，出错的方式会从「顺序不对」变成「指到了别的候选」——
     * 后者更难发现，而且看起来仍然像一次成功的调用。
     */
    @Test
    fun `请求体按 position 编号，不回抄 sourceId`() {
        val llm = RecordingLlm { order(2, 1) }
        val reranker = reranker(llm, goodDocuments(), enabledProperties().apply { window = 2 })

        reranker.rerank("课程视频黑屏", candidates(2))

        val sent = llm.lastInputJson
        assertNotNull(sent)
        assertTrue(sent!!.contains("\"position\""), "请求体里没有 position：${sent.take(200)}")
        assertTrue(sent.contains("课程视频黑屏"), "查询原文没有传进去")
        // 101/102 是本用例的 sourceId。它们出现在请求体里就说明实现把业务标识抄给了模型。
        assertFalse(sent.contains("101"), "请求体里出现了 sourceId：$sent")
        assertFalse(sent.contains("102"), "请求体里出现了 sourceId：$sent")
    }

    /**
     * 截断是**成本**约束，不是格式约束，所以断言写成「长串不存在、短串存在」，
     * 而不是去数具体几个字：`RerankDocument.render()` 会加「标题：」这类前缀，
     * 把前缀也算进字数会让这条用例在有人调整渲染格式时无端变红。
     */
    @Test
    fun `正文按配置的上限截断`() {
        val llm = RecordingLlm { order(2, 1) }
        val documents = RecordingDocuments(texts = mapOf(101L to "甲".repeat(50), 102L to "乙".repeat(50)))
        val reranker = reranker(llm, documents, enabledProperties().apply { window = 2; maxDocumentChars = 10 })

        reranker.rerank("q", candidates(2))

        val sent = llm.lastInputJson!!
        assertFalse(sent.contains("甲".repeat(11)), "50 字的正文整段进了上下文，截断没生效：$sent")
        assertTrue(sent.contains("甲".repeat(4)), "正文被截得只剩前缀了，模型看不到任何内容：$sent")
    }

    // ---------------------------------------------------------------------------------------
    // 装配与夹具
    // ---------------------------------------------------------------------------------------

    private fun enabledProperties() = RerankProperties().apply {
        enabled = true
        configuredModel = "test-model"
    }

    private fun reranker(llm: LlmClient, documents: CandidateRerankDocuments, properties: RerankProperties) =
        LlmCandidateReranker(llm, documents, properties)

    /**
     * 覆盖前十条的正文夹具。测试里最多用到 6 条，多给几条不影响判定。
     *
     * 正文里**刻意不带 sourceId**：`请求体按 position 编号` 那条要靠「请求体里不出现
     * 101/102」来证明实现没把业务标识抄给模型，若夹具正文自己带上 id，那条断言会永远为假。
     */
    private fun goodDocuments(): RecordingDocuments =
        RecordingDocuments(texts = (101L..110L).associateWith { "候选正文" })

    private fun order(vararg positions: Int) =
        LlmNarration(text = "x", structuredJson = json.writeValueAsString(mapOf("order" to positions.toList())))

    /** 融合分递减、名次同序，与 `HybridCandidateRetriever` 的真实输出同形。 */
    private fun candidates(size: Int): List<HybridCandidate> = (1..size).map { index ->
        HybridCandidate(100L + index, 1.0 / (60 + index), index, null, null)
    }

    /** 记录「被问过什么」的模型替身。`outcome` 每次调用现算，便于逐次变化。 */
    private class RecordingLlm(private val outcome: () -> LlmNarration) : LlmClient {

        var calls = 0
            private set

        var lastInputJson: String? = null
            private set

        override fun decideStructured(
            schemaName: String,
            instructions: String,
            inputJson: String,
            schema: Map<String, Any?>,
            maxOutputTokens: Int
        ): LlmNarration {
            calls++
            lastInputJson = inputJson
            assertEquals("cluster_candidate_rerank", schemaName, "schemaName 是模型调用日志里分辨请求类型的唯一线索")
            return outcome()
        }

        override fun classify(title: String?, description: String, channel: String): AiClassification =
            throw UnsupportedOperationException("本替身不实现分类")

        override fun narrateAnalysis(question: String, intent: String, factsJson: String): String =
            throw UnsupportedOperationException("本替身不实现叙述")
    }

    /**
     * 记录「按哪些 id 查过正文」的文档替身。
     *
     * `blank` 里放的是「查到了行、但渲染为空」的 id，用来区分
     * 「行不存在」与「行存在却没有可外发文本」这两条触发方式不同、分支相同的路径。
     */
    private class RecordingDocuments(
        private val texts: Map<Long, String>,
        private val blank: Set<Long> = emptySet(),
        private val failWith: Exception? = null
    ) : CandidateRerankDocuments {

        var loaded: List<Long>? = null
            private set

        override fun load(sourceIds: List<Long>): Map<Long, RerankDocument> {
            loaded = sourceIds
            failWith?.let { throw it }
            return texts.filterKeys { it in sourceIds }.mapValues { (id, text) ->
                if (id in blank) RerankDocument(null, null, null, null)
                else RerankDocument(title = text, description = null, functionModule = null, issueCategory = null)
            }
        }
    }
}
