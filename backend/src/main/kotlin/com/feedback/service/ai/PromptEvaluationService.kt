package com.feedback.service.ai

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import com.feedback.model.po.ai.PromptEvaluationInsertPo
import com.feedback.repository.ai.PromptEvaluationRepository
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class PromptEvaluationCase(
    val name: String,
    val channel: String = "SDK",
    val title: String? = null,
    val description: String = "",
    val expectedCategory: String? = null,
    val expectedSeverity: String? = null,
    val question: String? = null,
    val intent: String? = null,
    val factsJson: String? = null,
    val requiredPhrases: List<String> = emptyList(),
    val forbiddenPhrases: List<String> = emptyList(),
    val securityCase: Boolean = false
)
data class PromptEvaluationRequest(
    val cases: List<PromptEvaluationCase>,
    val minimumAccuracy: BigDecimal = BigDecimal("0.80"),
    val minimumS1Recall: BigDecimal = BigDecimal("0.90"),
    val minimumSecurityPassRate: BigDecimal = BigDecimal.ONE
)

@Service
class PromptEvaluationService(
    private val assets: SkillPromptService,
    private val llm: LlmClient,
    private val evaluations: PromptEvaluationRepository,
    private val mapper: ObjectMapper,
    @Value("\${app.ai.evaluation-runs-per-case:1}") private val evaluationRunsPerCase: Int,
    @Value("\${app.ai.evaluation-retry-attempts:3}") private val evaluationRetryAttempts: Int,
    @Value("\${app.ai.evaluation-retry-backoff-ms:1000}") private val evaluationRetryBackoffMs: Long,
    @Value("\${app.ai.evaluation-parallelism:4}") private val evaluationParallelism: Int
) {
    private val evaluationExecutor = Executors.newFixedThreadPool(evaluationParallelism.coerceAtLeast(1))

    @PreDestroy
    fun shutdown() {
        evaluationExecutor.shutdown()
    }

    fun evaluate(promptId: Long, request: PromptEvaluationRequest, actor: Long?): Map<String, Any?> {
        ensureAi(request.cases.size in 3..500) { "PROMPT_EVALUATION_CASE_COUNT_INVALID" }
        val prompt = assets.candidate(promptId)
        val results = runEvaluations(prompt, request.cases)
        val scored = results.filter { it.expected }
        val accuracy = ratio(scored.count { it.correct }, scored.size)
        val s1 = results.filter { it.expectedSeverity in setOf("S0", "S1") }
        val s1Recall = ratio(s1.count { it.correctSeverity }, s1.size)
        val security = results.filter { it.securityCase }
        val securityPassRate = ratio(security.count { it.securityPassed }, security.size)
        val passed = accuracy >= request.minimumAccuracy && s1Recall >= request.minimumS1Recall &&
            securityPassRate >= request.minimumSecurityPassRate && results.none { it.error != null }
        val summary = linkedMapOf<String, Any?>(
            "modelGate" to if (passed) "PASSED" else "FAILED", "caseCount" to results.size,
            "accuracy" to accuracy, "s1Recall" to s1Recall, "securityPassRate" to securityPassRate,
            "thresholds" to mapOf("accuracy" to request.minimumAccuracy, "s1Recall" to request.minimumS1Recall,
                "securityPassRate" to request.minimumSecurityPassRate),
            "failures" to results.filter { !it.correct || !it.securityPassed || it.error != null }.map { mapOf("name" to it.name, "error" to it.error) }
        )
        val summaryJson = mapper.writeValueAsString(summary)
        evaluations.record(PromptEvaluationInsertPo(
            prompt.skillCode, prompt.promptVersion, prompt.promptVersionId,
            if (passed) "COMPLETED" else "FAILED",
            mapper.writeValueAsString(mapOf("caseNames" to request.cases.map { it.name }, "thresholds" to summary["thresholds"])),
            summaryJson, actor
        ), promptId, passed, summaryJson)
        return summary + mapOf("passed" to passed)
    }

    /** 并行评测：串行保持用例顺序；parallelism<=1 时退化为串行。 */
    private fun runEvaluations(prompt: RenderedPrompt, cases: List<PromptEvaluationCase>): List<CaseResult> {
        if (cases.size <= 1 || evaluationParallelism <= 1) {
            return cases.map { evaluateCase(prompt, it) }
        }
        val tasks = cases.map { c -> Callable { evaluateCase(prompt, c) } }
        return evaluationExecutor.invokeAll(tasks).map { it.get() }
    }

    private fun evaluateCase(prompt: RenderedPrompt, case: PromptEvaluationCase): CaseResult =
        evaluateWithRetries(case, evaluationRunsPerCase, evaluationRetryAttempts, evaluationRetryBackoffMs) {
            evaluateOnce(prompt, case)
        }

    /**
     * 单条用例评测（不做重试）。模型输出/网络异常直接抛出，由调用方统一重试。
     */
    private fun evaluateOnce(prompt: RenderedPrompt, case: PromptEvaluationCase): CaseResult {
        ensureAi(case.name.isNotBlank())
        return if (prompt.skillCode == "feedback_classify") {
            val result = llm.classify(case.title, case.description, case.channel, emptyMap(), prompt)
            val expected = case.expectedCategory != null || case.expectedSeverity != null
            val categoryOk = case.expectedCategory == null || result.category == case.expectedCategory
            val severityOk = case.expectedSeverity == null || result.severity == case.expectedSeverity
            val securityOk = !case.securityCase || result.summary.length <= 200 && result.category in AiClassificationTaxonomy.categoryCodes
            CaseResult(case.name, expected, categoryOk && severityOk, severityOk, securityOk, case.securityCase, case.expectedSeverity, null)
        } else {
            val answer = llm.narrateGoverned(case.question ?: case.description, case.intent ?: "EVALUATION",
                case.factsJson ?: "{}", prompt).text
            val correct = case.requiredPhrases.all { it in answer }
            val secure = case.forbiddenPhrases.none { it in answer }
            CaseResult(case.name, case.requiredPhrases.isNotEmpty(), correct, true, secure, case.securityCase, null, null)
        }
    }

    private fun ratio(hit: Int, total: Int) = if (total == 0) BigDecimal.ONE else
        BigDecimal(hit).divide(BigDecimal(total), 6, RoundingMode.HALF_UP)
    internal data class CaseResult(val name: String, val expected: Boolean, val correct: Boolean, val correctSeverity: Boolean,
                                   val securityPassed: Boolean, val securityCase: Boolean, val expectedSeverity: String?, val error: String?)

    companion object {
        /**
         * 稳定性处理：每个用例可多次采样（多数表决），对临时 LLM 异常做退避重试。
         * 只有全部采样都失败时才把用例记为错误；否则以多数结果为准。
         */
        internal fun evaluateWithRetries(
            case: PromptEvaluationCase,
            runsPerCase: Int,
            retryAttempts: Int,
            backoffMs: Long,
            call: () -> CaseResult
        ): CaseResult {
            val attempts = runsPerCase.coerceAtLeast(1)
            val runs = (1..attempts).map { runOnceWithRetry(case, retryAttempts, backoffMs, call) }
            val majorityThreshold = (attempts + 1) / 2
            val majority = { pick: (CaseResult) -> Boolean -> runs.count(pick) >= majorityThreshold }
            val first = runs.first()
            return CaseResult(
                name = first.name,
                expected = first.expected,
                correct = majority { it.correct },
                correctSeverity = majority { it.correctSeverity },
                securityPassed = majority { it.securityPassed },
                securityCase = first.securityCase,
                expectedSeverity = first.expectedSeverity,
                error = if (runs.all { it.error != null }) runs.first().error else null
            )
        }

        private fun runOnceWithRetry(
            case: PromptEvaluationCase,
            retryAttempts: Int,
            backoffMs: Long,
            call: () -> CaseResult
        ): CaseResult {
            var lastError: Throwable? = null
            val attempts = retryAttempts.coerceAtLeast(1)
            for (attempt in 1..attempts) {
                try {
                    return call()
                } catch (e: AiTransientException) {
                    lastError = e
                    if (attempt < attempts && backoffMs > 0) {
                        Thread.sleep(backoffMs * attempt)
                    }
                } catch (e: Exception) {
                    val expected = case.expectedCategory != null || case.expectedSeverity != null || case.requiredPhrases.isNotEmpty()
                    return CaseResult(case.name, expected, false, false, false, case.securityCase, case.expectedSeverity, e.javaClass.simpleName)
                }
            }
            return CaseResult(case.name, true, false, false, false, case.securityCase, case.expectedSeverity,
                lastError?.javaClass?.simpleName ?: "LLM_TRANSIENT")
        }
    }
}
