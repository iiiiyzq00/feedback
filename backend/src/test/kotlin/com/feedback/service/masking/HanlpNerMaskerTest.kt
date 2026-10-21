package com.feedback.service.masking

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HanlpNerMaskerTest {
    private val masker = HanlpNerMasker(true)

    @Test
    fun `recognizes an unstructured chinese person name`() {
        val result = masker.mask("学生张小明今天无法登录系统")

        assertTrue(result.available)
        assertFalse(result.text.contains("张小明"))
        assertTrue(result.findings.any { it.type == "NAME" && it.originalValue == "张小明" })
    }

    @Test
    fun `disabled ner is an explicit safe configuration`() {
        val result = HanlpNerMasker(false).mask("张小明无法登录")

        assertTrue(result.available)
        assertEquals("张小明无法登录", result.text)
    }

    @Test
    fun `does not mask common product and interaction terms as names`() {
        val input = "连续切换时页面卡顿，横屏画布右侧偏移，今日步数没有更新"
        val result = masker.mask(input)

        assertEquals(input, result.text)
        assertTrue(result.findings.none { it.type == "NAME" })
    }

}
