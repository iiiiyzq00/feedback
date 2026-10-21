package com.feedback.service.ai

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import com.feedback.repository.ai.AiQuotaRepository

@Component
class LlmQuotaGuard(
    private val quota: AiQuotaRepository,
    @Value("\${app.ai.llm.daily-token-quota:0}") private val dailyQuota: Long
) {
    fun requireAvailable() {
        if (dailyQuota <= 0) return
        val used = quota.usedToday()
        ensureAi(used < dailyQuota) { "LLM_DAILY_TOKEN_QUOTA_EXHAUSTED" }
    }
}
