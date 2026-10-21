package com.feedback.agent.rca.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * span 推导的纯函数契约：**确定性**、**形状**、**没有 trace 就没有 span**。
 *
 * ## 为什么确定性这一条必须单独钉住
 *
 * `t_rca_tool_call` 的去重键是 `(任务, 工具, 参数摘要, 轮次)`，它的语义是
 * 「**同一次逻辑调用**」。崩溃恢复会重跑同一个节点，于是同一次逻辑调用会被再走一遍：
 *
 * - span 随机 → 这一次调用在库里留下两个互不相干的 span，去重键说「同一次」、
 *   span 说「两次」，两个事实互相矛盾。排障时按 trace 串调用链，
 *   会看到同一次调用出现两遍，且**无法判断哪一遍是真发生过的**。
 * - span 确定 → 重放写出的还是同一个 span，去重键与 span 讲的是同一件事。
 *
 * 这个错误不会让任何东西报错，只会让调用链在排障时悄悄多出一份影子记录，
 * 所以它值得一条直接断言，而不是靠「端到端跑一遍看起来没问题」。
 *
 * 形状与 `chk_rca_tool_span`（16 位小写十六进制）对齐：写进库的那一列是靠这条约束
 * 兜底的，而这里验的是**写入侧先就产出了合法形状**——真到库上报错时，
 * 已经错过一次工具调用（`begin` 在发请求之前）。
 */
class ToolSpanIdFactoryTest {

    @Test
    fun `同一次逻辑调用无论推导多少次都得到同一个 span`() {
        val first = ToolSpanIdFactory.of(TRACE_ID, TOOL_NAME, DIGEST, ROUND)
        val second = ToolSpanIdFactory.of(TRACE_ID, TOOL_NAME, DIGEST, ROUND)

        assertEquals(
            first, second,
            "span 不是确定性的：崩溃重放后同一次逻辑调用会有两个 span，" +
                "去重键说「同一次」而 span 说「两次」"
        )
    }

    @Test
    fun `四要素里任何一个变了，span 就不同`() {
        val base = ToolSpanIdFactory.of(TRACE_ID, TOOL_NAME, DIGEST, ROUND)

        // 逐项改一个要素。少了一样就等于把两次不同的取证合并成一个 span，
        // 而它们确实是两次（不同工具、不同参数、或不同轮次提出的不同假设）。
        assertNotEquals(
            base, ToolSpanIdFactory.of(OTHER_TRACE, TOOL_NAME, DIGEST, ROUND),
            "换了 trace 却得到同一个 span：两次不同的任务执行会被串成一条链"
        )
        assertNotEquals(
            base, ToolSpanIdFactory.of(TRACE_ID, "VERSION_COHORT", DIGEST, ROUND),
            "换了工具却得到同一个 span"
        )
        assertNotEquals(
            base, ToolSpanIdFactory.of(TRACE_ID, TOOL_NAME, OTHER_DIGEST, ROUND),
            "换了参数摘要却得到同一个 span"
        )
        assertNotEquals(
            base, ToolSpanIdFactory.of(TRACE_ID, TOOL_NAME, DIGEST, ROUND + 1),
            "换了轮次却得到同一个 span：同一工具为不同假设发起的调用会被合并"
        )
    }

    @Test
    fun `形状是 16 位小写十六进制`() {
        val span = ToolSpanIdFactory.of(TRACE_ID, TOOL_NAME, DIGEST, ROUND)!!

        assertEquals(16, span.length, "span 的宽度必须与 W3C 的 span-id 一致")
        assertTrue(
            span.matches(Regex("^[0-9a-f]{16}$")),
            "span 不是 16 位小写十六进制：chk_rca_tool_span 会拒掉它，而那时工具调用已经发出去了"
        )
    }

    /**
     * 没有 trace 就没有 span——**返回 null**，而不是用别的字段凑一个。
     *
     * 凑出来的 span 会挂在一个不存在的 trace 下：它既不能被任何一条链路捞出来，
     * 又让「这次调用有没有链路标识」这个问题的答案从「没有」变成「有，但很怪」。
     * 库上的 `chk_rca_tool_trace_span_pair` 把它固化成了一条不变式，这里是它的写入侧。
     */
    @Test
    fun `没有 trace 时返回 null 而不是凑一个`() {
        assertNull(ToolSpanIdFactory.of(null, TOOL_NAME, DIGEST, ROUND))
    }

    private companion object {
        const val TRACE_ID = "0123456789abcdef0123456789abcdef"
        const val OTHER_TRACE = "fedcba9876543210fedcba9876543210"
        const val TOOL_NAME = "LOG_TRACE"
        const val DIGEST = "c0ffee000000000000000000000000000000000000000000000000000000beef"
        const val OTHER_DIGEST = "dead0000000000000000000000000000000000000000000000000000000000ff"
        const val ROUND = 2
    }
}
