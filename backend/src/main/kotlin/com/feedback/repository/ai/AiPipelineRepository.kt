package com.feedback.repository.ai

import com.feedback.common.SafeErrorDetail
import com.feedback.mapper.ai.*
import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import com.feedback.mapper.cluster.ClusterContentTableMapper
import com.feedback.mapper.cluster.ClusterSourceTableMapper
import org.springframework.stereotype.Repository

/**
 * 本次领取的 AI 任务。leaseHolder + leaseGeneration 是 claim 发出的 fencing token，
 * 必须原样回传到 retry/complete，才能证明写回的还是本次租约。
 */
data class ClaimedAiTask(val id: Long, val retryCount: Int, val leaseHolder: String? = null,
                         val leaseGeneration: Long = 0)

@Repository
class AiQuotaRepository(private val tasks: AiTaskMapper) {
    fun usedToday(): Long = tasks.sumTodayGovernedTokens()
}

@Repository
class AiNarrationShadowRepository(private val tasks: AiTaskMapper) {
    fun insert(row: AiNarrationShadowInsertPo) {
        ensureAiRepositoryState(tasks.insertNarrationShadow(row) == 1) { "AI_NARRATION_SHADOW_INSERT_FAILED" }
    }
}

@Repository
class GovernedSkillInvocationRepository(private val tasks: AiTaskMapper) {
    fun insert(row: GovernedSkillInvocationInsertPo) {
        ensureAiRepositoryState(tasks.insertGovernedInvocation(row) in 0..1) { "GOVERNED_SKILL_INVOCATION_INSERT_FAILED" }
    }
}

@Repository
class PromptEvaluationRepository(private val tasks: AiTaskMapper) {
    fun record(row: PromptEvaluationInsertPo, promptId: Long, passed: Boolean, summary: String) {
        ensureAiRepositoryState(tasks.insertPromptEvaluation(row) == 1) { "PROMPT_EVALUATION_INSERT_FAILED" }
        ensureAiRepositoryState(tasks.updatePromptEvaluation(promptId, passed, summary) == 1) { "PROMPT_EVALUATION_TARGET_NOT_FOUND" }
    }
}

data class PromptRollbackCandidate(val skillCode: String, val promptVersion: String, val total: Int, val failures: Int)

@Repository
class PromptRollbackRepository(private val tasks: AiTaskMapper) {
    fun candidates(minimum: Int): List<PromptRollbackCandidate> {
        val stats = tasks.findRecentInvocationStats().associateBy { it.skillCode to it.promptVersion }
        return tasks.findRollbackCandidates().mapNotNull { prompt ->
            val metric = stats[prompt.skillCode to prompt.promptVersion] ?: return@mapNotNull null
            if (metric.total < minimum) null else PromptRollbackCandidate(prompt.skillCode, prompt.promptVersion,
                metric.total, metric.failures)
        }
    }
}

@Repository
class AiPipelineRepository(
    private val tasks: AiTaskMapper,
    private val sources: ClusterSourceTableMapper,
    private val contents: ClusterContentTableMapper,
    private val sdk: AiSdkContextMapper,
    private val jira: AiJiraContextMapper,
    private val quality: AiQualityContextMapper,
    private val redblack: AiRedblackContextMapper
) {
    /** 先分表批量关闭来源失效任务，再在同一事务内 SKIP LOCKED 认领一个有效任务。 */
    fun claim(workerId: String): ClaimedAiTask? {
        closeUnavailable()
        val row = tasks.lockClaimCandidates(1).singleOrNull() ?: return null
        ensureAiRepositoryState(tasks.claim(row.id, workerId) in 0..1) { "AI_TASK_CLAIM_AFFECTED_ROWS_INVALID" }
        // generation 由 claim 在数据库里自增，必须回读当前行才能拿到本次领取真正生效的 fencing token。
        val claimed = tasks.findById(row.id)
        return if (claimed != null && claimed.leaseHolder == workerId) {
            ClaimedAiTask(row.id, row.retryCount, workerId, claimed.leaseGeneration)
        } else null
    }

    fun retry(taskId: Long, holder: String, generation: Long, code: String, terminal: Boolean) {
        val affected = if (terminal) tasks.terminalFailure(taskId, holder, generation, code)
        else tasks.retryFailure(taskId, holder, generation, code)
        ensureAiRepositoryState(affected in 0..1) { "AI_TASK_RETRY_AFFECTED_ROWS_INVALID" }
    }

    fun complete(taskId: Long, holder: String, generation: Long, output: String, error: String?) {
        ensureAiRepositoryState(tasks.completeLease(taskId, holder, generation, output, error) == 1) { "AI_TASK_LEASE_LOST" }
    }

    fun updatePromptSnapshot(row: AiPromptSnapshotUpdatePo) {
        ensureAiRepositoryState(tasks.updatePromptSnapshot(row) == 1) { "AI_TASK_PROMPT_SNAPSHOT_UPDATE_FAILED" }
    }

    fun insertShadow(row: AiShadowEvaluationInsertPo) {
        ensureAiRepositoryState(tasks.insertShadow(row) == 1) { "AI_SHADOW_EVALUATION_INSERT_FAILED" }
    }

    fun enqueueSourceIds(limit: Int): List<Long> {
        ensureAiRepositoryInput(limit in 1..200) { "AI_ENQUEUE_LIMIT_INVALID" }
        val version = tasks.findCurrentPromptVersion("feedback_classify") ?: return emptyList()
        val result = mutableListOf<Long>()
        var after = 0L
        while (result.size < limit) {
            val page = contents.findEligibleAfter(after, 200)
            if (page.isEmpty()) break
            val keys = page.associate { it.sourceId to "feedback_classify:${it.sourceId}:$version" }
            val existing = tasks.findTaskKeys(keys.values.toList()).toSet()
            result += keys.filterValues { it !in existing }.keys.take(limit - result.size)
            after = page.last().sourceId
            if (page.size < 200) break
        }
        return result
    }

    fun channelContext(sourceId: Long, channel: String): Map<String, Any?> = when (channel) {
        "SDK" -> {
            val content = contents.findByIds(listOf(sourceId)).singleOrNull()
            val context = sdk.findBySourceId(sourceId)
            if (content == null || context == null) emptyMap() else linkedMapOf(
                "issue_type" to content.problemType,
                "function_module" to content.functionModule,
                "usage_stage" to content.usageStage,
                "input_methods" to context.inputMethods
            )
        }
        "JIRA" -> jira.findBySourceId(sourceId)?.let {
            mapOf("issue_type_raw" to it.issueTypeRaw, "priority_raw" to it.priorityRaw, "environment" to it.environment)
        } ?: emptyMap()
        "QUALITY" -> quality.findBySourceId(sourceId)?.let {
            mapOf("application_type_raw" to it.applicationTypeRaw, "exchange_request" to it.exchangeRequest,
                "return_reason" to it.returnReason)
        } ?: emptyMap()
        "REDBLACK" -> redblack.findBySourceId(sourceId)?.let {
            mapOf("source_platform" to it.sourcePlatform, "emotion_type_raw" to it.emotionTypeRaw,
                "origin_message" to it.originMessage)
        } ?: emptyMap()
        else -> emptyMap()
    }

    private fun closeUnavailable() {
        var after = 0L
        while (true) {
            val page = tasks.findAvailabilityCandidates(after, BATCH)
            if (page.isEmpty()) break
            val sourceIds = page.mapNotNull { it.sourceId }.distinct().sorted()
            val sourceSet = if (sourceIds.isEmpty()) emptySet() else sources.findByIds(sourceIds).map { it.id }.toSet()
            val contentById = if (sourceIds.isEmpty()) emptyMap() else contents.findByIds(sourceIds).associateBy { it.sourceId }
            val invalid = page.filter { task ->
                val sourceId = task.sourceId
                val content = sourceId?.let(contentById::get)
                sourceId == null || sourceId !in sourceSet || content == null || content.isDeleted || content.maskingStatus != "PASS"
            }.map { it.id }
            invalid.chunked(BATCH).forEach(tasks::cancelUnavailable)
            after = page.last().id
            if (page.size < BATCH) break
        }
    }

    companion object { const val BATCH = 200 }
}
