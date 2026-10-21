package com.feedback.service.ai

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import com.feedback.model.po.ai.AiNarrationShadowInsertPo
import com.feedback.repository.ai.AiNarrationShadowRepository

@Service
class NarrationShadowEvaluator(
    private val assets: SkillPromptService,
    private val llm: LlmClient,
    private val shadows: AiNarrationShadowRepository,
    private val mapper: ObjectMapper
) {
    fun evaluate(skill: String, stableKey: String, primary: RenderedPrompt, question: String, intent: String,
                 factsJson: String, primaryText: String) {
        val shadow = assets.shadowForInvocation(skill, stableKey, primary.promptVersionId) ?: return
        val result = runCatching { llm.narrateGoverned(question, intent, factsJson, shadow) }
        val narration = result.getOrNull()
        shadows.insert(AiNarrationShadowInsertPo(
            skill, shadow.promptVersion, shadow.promptVersionId, if (result.isSuccess) "COMPLETED" else "FAILED",
            mapper.writeValueAsString(mapOf("mode" to "SHADOW", "primaryVersion" to primary.promptVersion)),
            mapper.writeValueAsString(mapOf("primaryAnswer" to primaryText.take(500),
                "shadowAnswer" to narration?.text?.take(500))), narration?.latencyMs, narration?.inputTokens,
            narration?.outputTokens, narration?.estimatedCostUsd, narration?.providerRequestId,
            shadow.skillVersion, shadow.contentSha256, result.exceptionOrNull()?.javaClass?.simpleName
        ))
    }
}
