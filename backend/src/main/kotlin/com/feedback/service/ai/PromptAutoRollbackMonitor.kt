package com.feedback.service.ai

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import com.feedback.repository.ai.PromptRollbackRepository

@Component
class PromptAutoRollbackMonitor(
    private val rollbacks: PromptRollbackRepository,
    private val assets: SkillPromptService,
    @Value("\${app.ai.auto-rollback-failure-rate:0.10}") private val failureRate: Double,
    @Value("\${app.ai.auto-rollback-min-invocations:20}") private val minimumInvocations: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ensureAiState() {
        val candidates = rollbacks.candidates(minimumInvocations)
        candidates.forEach { row ->
            val total = row.total.toDouble(); val failures = row.failures.toDouble()
            if (AiGovernancePolicy.shouldRollback(total.toInt(), failures.toInt(), minimumInvocations, failureRate)) runCatching {
                assets.emergencyRollback(row.skillCode, null, "AUTO_FAILURE_RATE:${failures.toInt()}/${total.toInt()}")
            }.onSuccess { log.error("Automatically rolled back AI prompt skill={} version={} failureRate={}",
                row.skillCode, row.promptVersion, failures / total) }
             .onFailure { log.error("AI prompt automatic rollback failed skill={}", row.skillCode, it) }
        }
    }
}
