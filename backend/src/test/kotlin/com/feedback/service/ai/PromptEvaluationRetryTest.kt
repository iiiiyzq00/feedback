package com.feedback.service.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptEvaluationRetryTest {
    private val case = PromptEvaluationCase(name = "c1", channel = "SDK", description = "测试", expectedCategory = "BUG")
    private val ok = PromptEvaluationService.CaseResult("c1", true, true, true, true, false, null, null)
    private val wrong = PromptEvaluationService.CaseResult("c1", true, false, false, true, false, null, null)

    @Test
    fun `retries transient LLM errors then succeeds`() {
        var calls = 0
        val result = PromptEvaluationService.evaluateWithRetries(
            case, runsPerCase = 1, retryAttempts = 3, backoffMs = 0
        ) {
            calls++
            if (calls <= 2) throw AiTransientException("LLM_HTTP_500")
            ok
        }
        assertEquals(3, calls)
        assertTrue(result.correct)
        assertNull(result.error)
    }

    @Test
    fun `does not retry non transient errors`() {
        var calls = 0
        val result = PromptEvaluationService.evaluateWithRetries(
            case, runsPerCase = 1, retryAttempts = 3, backoffMs = 0
        ) {
            calls++
            throw IllegalArgumentException("bad input")
        }
        assertEquals(1, calls)
        assertEquals("IllegalArgumentException", result.error)
    }

    @Test
    fun `marks case failed when all runs error`() {
        val result = PromptEvaluationService.evaluateWithRetries(
            case, runsPerCase = 1, retryAttempts = 2, backoffMs = 0
        ) {
            throw AiTransientException("LLM_TIMEOUT")
        }
        assertEquals("AiTransientException", result.error)
        assertTrue(!result.correct)
    }

    @Test
    fun `majority vote over multiple runs`() {
        val passSeq = ArrayDeque(listOf(ok, wrong, ok))
        val majorityPass = PromptEvaluationService.evaluateWithRetries(
            case, runsPerCase = 3, retryAttempts = 1, backoffMs = 0
        ) {
            passSeq.removeFirst()
        }
        assertTrue(majorityPass.correct)
        assertNull(majorityPass.error)

        val failSeq = ArrayDeque(listOf(ok, wrong, wrong))
        val majorityFail = PromptEvaluationService.evaluateWithRetries(
            case, runsPerCase = 3, retryAttempts = 1, backoffMs = 0
        ) {
            failSeq.removeFirst()
        }
        assertTrue(!majorityFail.correct)
    }
}
