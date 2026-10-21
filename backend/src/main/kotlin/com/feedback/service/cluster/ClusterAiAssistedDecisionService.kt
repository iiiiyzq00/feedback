package com.feedback.service.cluster

import com.feedback.common.SafeErrorDetail
import com.feedback.common.ErrorCode
import com.feedback.service.ai.ClusterAiAdvice
import com.feedback.service.ai.ClusterAiPromptContract
import com.feedback.service.ai.LlmClient
import com.feedback.repository.cluster.ClusterAiTaskRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

enum class ClusterAiResolutionStatus { DISABLED, PENDING, COMPLETED, FAILED }
data class ClusterAiResolution(val status: ClusterAiResolutionStatus, val advice: ClusterAiAdvice? = null)

@Service
class ClusterAiAssistedDecisionService(
    private val tasks: ClusterAiTaskRepository,
    private val mapper: ObjectMapper,
    private val llm: LlmClient,
    @Value("\${app.ai.cluster-assisted-decision-enabled:false}") private val enabled: Boolean,
    @Value("\${app.ai.cluster-assisted-decision-max-retries:1}") private val maxRetries: Int
) {
    fun resolveOrEnqueue(sourceId: Long, profile: ProblemProfile, plan: ClusterDecisionPlan,
                         recalledCandidates: List<CandidateEvidence>): ClusterAiResolution {
        if (!enabled) return ClusterAiResolution(ClusterAiResolutionStatus.DISABLED)
        val taskKey = "CLUSTER_AI_DECISION:$sourceId"
        val existing = tasks.decision(taskKey)
        if (existing != null) return when (existing.status) {
            "COMPLETED" -> runCatching {
                parseAdvice(existing.outputSnapshot.orEmpty())
            }.map { advice ->
                if (validAdvice(sourceId, advice, recalledCandidates)) {
                    ClusterAiResolution(ClusterAiResolutionStatus.COMPLETED, advice)
                } else {
                    ClusterAiResolution(ClusterAiResolutionStatus.FAILED)
                }
            }.getOrElse { ClusterAiResolution(ClusterAiResolutionStatus.FAILED) }
            "FAILED", "CANCELLED" -> ClusterAiResolution(ClusterAiResolutionStatus.FAILED)
            else -> ClusterAiResolution(ClusterAiResolutionStatus.PENDING)
        }
        val facts = facts(sourceId, profile, plan, recalledCandidates)
        tasks.enqueueDecision(sourceId, taskKey, ClusterAiPromptContract.VERSION, SKILL_VERSION,
            ClusterAiPromptContract.sha256, mapper.writeValueAsString(facts), maxRetries)
        return ClusterAiResolution(ClusterAiResolutionStatus.PENDING)
    }

    fun processDue() {
        if (!enabled) return
        tasks.recoverExpired()
        tasks.dueIds(10).forEach(::process)
    }

    internal fun process(taskId: Long) {
        val holder = "cluster-ai-${UUID.randomUUID()}"
        if (!tasks.claim(taskId, holder)) return
        try {
            val input = tasks.decision(taskId)?.inputSnapshot
                ?: clusterInvariantViolation("CLUSTER_AI_INPUT_MISSING", ErrorCode.FEEDBACK_READ_UNAVAILABLE)
            val advice = llm.adviseCluster(input)
            val output = mapper.writeValueAsString(mapOf(
                "recommendedAction" to advice.recommendedAction, "selectedClusterRef" to advice.selectedClusterRef,
                "canonicalSymptom" to advice.canonicalSymptom, "explanation" to advice.explanation,
                "evidenceRefs" to advice.evidenceRefs, "riskSignals" to advice.riskSignals,
                "confidence" to advice.confidence
            ))
            tasks.complete(taskId, holder, output, advice.modelName, advice.providerRequestId,
                advice.inputTokens, advice.outputTokens, advice.latencyMs, advice.estimatedCostUsd)
        } catch (error: Exception) {
            tasks.fail(taskId, holder, SafeErrorDetail.summary(error, "CLUSTER_AI_DECISION"))
        }
    }

    private fun facts(sourceId: Long, profile: ProblemProfile, plan: ClusterDecisionPlan,
                      recalledCandidates: List<CandidateEvidence>): Map<String, Any?> {
        val candidates = recalledCandidates.map { evidence -> mapOf(
            "evidenceRef" to "cluster:${evidence.cluster.id}", "clusterId" to evidence.cluster.id,
            "canonicalSymptom" to (evidence.cluster.canonicalSymptom ?: evidence.cluster.clusterName),
            "functionModule" to evidence.cluster.functionModule, "issueCategory" to evidence.cluster.issueCategory,
            "semanticScore" to evidence.semanticScore, "rankingScore" to evidence.rankingScore,
            "context" to mapOf("versionMatch" to evidence.versionMatch,
                "timeDistanceHours" to evidence.timeDistanceHours,
                "crossChannelEvidence" to evidence.crossChannelEvidence),
            "vectorScore" to evidence.vectorScore,
            "coreMatches" to evidence.coreMatches.sorted(), "conflicts" to evidence.conflicts.sorted(),
            "crossChannelEvidence" to evidence.crossChannelEvidence, "historicalRecurrence" to evidence.historicalRecurrence
        ) }
        val sourceRef = "source:$sourceId"
        val clusterRefs = candidates.map { it.getValue("evidenceRef").toString() }
        return linkedMapOf("sourceId" to sourceId, "sourceEvidenceRef" to sourceRef, "profile" to profile,
            "deterministicPlan" to mapOf("action" to plan.action, "reason" to plan.reason), "candidates" to candidates,
            "allowedClusterRefs" to clusterRefs, "allowedEvidenceRefs" to (listOf(sourceRef) + clusterRefs))
    }

    private fun parseAdvice(json: String): ClusterAiAdvice {
        val value = mapper.readTree(json)
        return ClusterAiAdvice(value.path("recommendedAction").asText(), value.path("selectedClusterRef").takeUnless { it.isNull }?.asText(),
            value.path("canonicalSymptom").asText(), value.path("explanation").asText(),
            value.path("evidenceRefs").map { it.asText() }, value.path("riskSignals").map { it.asText() },
            value.path("confidence").decimalValue())
    }

    private fun validAdvice(sourceId: Long, advice: ClusterAiAdvice, candidates: List<CandidateEvidence>): Boolean {
        val sourceRef = "source:$sourceId"
        val allowedClusterRefs = candidates.map { "cluster:${it.cluster.id}" }.toSet()
        val evidence = advice.evidenceRefs.toSet()
        if (advice.recommendedAction !in setOf("CREATE_NEW", "LINK_EXISTING")) return false
        if (!ProductPhenomenonFormatter.isConcretePhenomenon(advice.canonicalSymptom) || sourceRef !in evidence) return false
        if (!evidence.all { it == sourceRef || it in allowedClusterRefs }) return false
        if (advice.recommendedAction == "CREATE_NEW") return advice.selectedClusterRef.isNullOrBlank()
        val selected = advice.selectedClusterRef ?: return false
        return selected in allowedClusterRefs && selected in evidence
    }

    companion object { const val SKILL_VERSION = "2.0.0" }
}
