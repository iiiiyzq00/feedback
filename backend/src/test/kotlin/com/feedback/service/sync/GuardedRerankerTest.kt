package com.feedback.service.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [GuardedReranker] 的契约：**只能换顺序，不能改集合，不许抛出**。
 *
 * ## 为什么每一条破坏方式都要单独一例
 *
 * 三条约束对应三种不同的下游后果（见 [GuardedReranker] 的表格），
 * 而它们在本层是三个不同的判断分支：条数不等、数量计数不匹配、抛异常。
 * 只写一个「乱来的重排器」测一次，就分不清是哪一条分支在起作用——
 * 将来某条分支被改坏时，测试仍然会通过。
 *
 * ## 为什么用「注入一个会乱来的实现」而不是断言正常路径
 *
 * 正常路径（返回一个合法排列）在这里几乎不会被违反，所以它测不出本层的价值；
 * 本层存在的理由就是拦住违反契约的实现。测试里的恶意实现正是线上会出现的形状：
 * 一个没读懂契约的人写了 `sortedBy { ... }` 却顺手加了 `.filter`，
 * 或者模型实现忘了规整输出。
 */
class GuardedRerankerTest {

    // ---------------------------------------------------------------------------------------
    // 正常路径
    // ---------------------------------------------------------------------------------------

    @Test
    fun `合法排列原样通过`() {
        val input = candidates(3)
        val guard = GuardedReranker(Reranker { _, list -> list.reversed() })

        val result = guard.rerank("q", input)

        // 断言的是「委托给出的顺序生效了」而不是「等于入参」：否则一个恒等的守卫也会通过。
        assertEquals(input.map { it.sourceId }.reversed(), result.map { it.sourceId })
    }

    @Test
    fun `少于两条候选时连委托都不调用`() {
        var invoked = false
        val guard = GuardedReranker(Reranker { _, list -> invoked = true; list })

        assertEquals(emptyList<Long>(), guard.rerank("q", emptyList()).map { it.sourceId })
        assertEquals(listOf(101L), guard.rerank("q", candidates(1)).map { it.sourceId })
        assertTrue(!invoked, "空列表与单元素列表没有顺序可言，不该产生一次（可能收费的）调用")
    }

    // ---------------------------------------------------------------------------------------
    // 三种破坏方式
    // ---------------------------------------------------------------------------------------

    @Test
    fun `实现删掉元素时退回融合顺序`() {
        val input = candidates(4)
        val guard = GuardedReranker(Reranker { _, list -> list.drop(1) })

        assertEquals(
            input.map { it.sourceId },
            guard.rerank("q", input).map { it.sourceId },
            "少了元素却放行：调用方会用返回条数判断召回是否被截断，删几条会把一次正常截断报成没截断"
        )
    }

    @Test
    fun `实现添加元素时退回融合顺序`() {
        val input = candidates(3)
        val guard = GuardedReranker(Reranker { _, list -> list + HybridCandidate(9_999, 0.0, 1, 1, null) })

        assertEquals(
            input.map { it.sourceId },
            guard.rerank("q", input).map { it.sourceId },
            "多了元素却放行：textRank/vectorRank 是「确实被引擎召回」的证据，凭空加一条就是伪造一次召回"
        )
    }

    /**
     * 重复与删/加是**同一个后果的两种写法**，但触发的是不同的判断分支。
     *
     * 用去重后的集合做判定时，`[A, A]` 与 `[A, B]` 看起来相等（都含 A），
     * 于是 B 已经消失却仍然放行。本类因此用计数而不是集合。
     */
    @Test
    fun `实现重复元素时退回融合顺序`() {
        val input = candidates(3)
        val guard = GuardedReranker(Reranker { _, list -> listOf(list[0], list[0], list[2]) })

        assertEquals(
            input.map { it.sourceId },
            guard.rerank("q", input).map { it.sourceId },
            "把 A 重复两次、丢掉 B 却放行：集合判定看不出这种形状"
        )
    }

    @Test
    fun `实现抛异常时不穿透，退回融合顺序`() {
        val input = candidates(3)
        val guard = GuardedReranker(Reranker { _, _ -> throw IllegalStateException("上游挂了") })

        assertEquals(input.map { it.sourceId }, guard.rerank("q", input).map { it.sourceId })
    }

    @Test
    fun `实现抛出的是 Error 而不是 Exception 时仍然穿透`() {
        val input = candidates(3)
        val guard = GuardedReranker(Reranker { _, _ -> throw OutOfMemoryError("boom") })

        // 这是刻意的边界：`Error` 不是「重排失败」，是 JVM 出了事。
        // 把它一起吞掉会让一个真正的资源耗尽被记成一次普通的重排降级。
        assertThrowsOutOfMemory { guard.rerank("q", input) }
    }

    private fun assertThrowsOutOfMemory(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Error 被吞掉了：资源耗尽会被记成一次普通的重排降级")
        } catch (expected: OutOfMemoryError) {
            // 期望路径
        }
    }

    // ---------------------------------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------------------------------

    /** 融合分递减，模仿真实融合结果（同一份入参永远得到同一份顺序）。 */
    private fun candidates(size: Int): List<HybridCandidate> = (1..size).map { index ->
        HybridCandidate(
            sourceId = 100L + index,
            fusedScore = 1.0 / (60 + index),
            textRank = index,
            vectorRank = null,
            vectorScore = null
        )
    }
}
