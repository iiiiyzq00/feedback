package com.feedback.service.ai

import com.feedback.model.ai.AiTask
import com.feedback.model.feedback.FeedbackAi
import com.feedback.repository.*
import com.feedback.repository.ai.AiPipelineRepository
import com.feedback.model.po.ai.AiPromptSnapshotUpdatePo
import com.feedback.model.po.ai.AiShadowEvaluationInsertPo
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

typealias ClaimedAiTask = com.feedback.repository.ai.ClaimedAiTask

@Service
class AiTaskService(
    private val tasks: AiTaskRepository,
    private val contents: FeedbackContentRepository,
    private val mapper: ObjectMapper,
    private val skillPrompts: SkillPromptService? = null
) {
    fun enqueue(sourceId: Long, promptVersion: String? = null): AiTask? {
        val content = contents.findById(sourceId).orElse(null) ?: return null
        if (content.maskingStatus != "PASS" || content.isDeleted) return null
        val selectedVersion = promptVersion ?: skillPrompts?.selectForInvocation("feedback_classify", sourceId.toString())?.promptVersion ?: PROMPT_VERSION
        val key = "feedback_classify:$sourceId:$selectedVersion"
        tasks.findByTaskKey(key)?.let { existing ->
            if (existing.taskStatus == "CANCELLED" && existing.errorDetail == "AI_SOURCE_UNAVAILABLE") {
                existing.taskStatus = "PENDING"
                existing.retryCount = 0
                existing.nextRetryAt = null
                existing.completedAt = null
                existing.errorDetail = null
                existing.updatedAt = LocalDateTime.now()
                return tasks.save(existing)
            }
            return existing
        }
        return try {
            tasks.saveAndFlush(AiTask(
                skillCode = "feedback_classify", promptVersion = selectedVersion, sourceId = sourceId,
                taskKey = key, taskStatus = "PENDING",
                inputSnapshot = mapper.writeValueAsString(mapOf(
                    "source_id" to sourceId, "title" to content.title, "description" to content.description,
                    "function_module" to content.functionModule
                )),
                snapshotRetentionUntil = LocalDateTime.now().plusDays(30)
            ))
        } catch (_: DuplicateKeyException) {
            tasks.findByTaskKey(key)
        }
    }

    companion object { const val PROMPT_VERSION = "classify-v1" }
}

@Service
class AiTaskClaimService(private val repository: AiPipelineRepository) {
    @Transactional
    fun claim(workerId: String): ClaimedAiTask? = repository.claim(workerId)

    @Transactional
    fun retry(taskId: Long, leaseHolder: String, generation: Long, error: Throwable) {
        val chain = generateSequence(error) { it.cause }.toList()
        val root = chain.last()
        val constraint = root.message?.let {
            Regex("Check constraint '([^']+)'", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }
        val code = "${root.javaClass.simpleName}${constraint?.let { name -> ":$name" }.orEmpty()}".take(50)
        val terminal = chain.any { (it as? com.feedback.common.exception.ClientException)?.errorCode == com.feedback.common.ErrorCode.NOT_FOUND || it is com.feedback.service.masking.OutboundPrivacyException }
        repository.retry(taskId, leaseHolder, generation, code, terminal)
    }
}

@Service
class AiTaskProcessor(
    private val tasks: AiTaskRepository,
    private val sources: FeedbackSourceRepository,
    private val contents: FeedbackContentRepository,
    private val results: FeedbackAiRepository,
    private val rules: RuleClassifier,
    private val llm: LlmClient,
    private val mapper: ObjectMapper,
    private val skillPrompts: SkillPromptService? = null,
    private val persistence: AiPipelineRepository? = null,
    private val runtimeConfig: com.feedback.config.RuntimeConfigService? = null,
    private val outboundPrivacy:com.feedback.service.masking.OutboundPrivacyGate?=null,
    private val safetyGuard:AiSafetyGuard?=null,
    private val severityPolicy: SeverityPolicyService? = null
) {
    @Transactional
    fun process(taskId: Long, forceFallback: Boolean = false): AiClassification {
        val task = tasks.findById(taskId).orElseThrow()
        ensureAi(task.skillCode == "feedback_classify") { "AI_TASK_SKILL_MISMATCH" }
        val sourceId = aiRequired(task.sourceId)
        val content = contents.findById(sourceId).orElseThrow()
        ensureAi(content.maskingStatus == "PASS" && !content.isDeleted) { "AI_INPUT_NOT_PASS" }
        val source = sources.findById(sourceId).orElseThrow()
        val sourceResult = content.issueCategory?.let(AiClassificationTaxonomy::normalizeCategory)?.let { category ->
            content.severityLevel?.takeIf { it in AiClassificationTaxonomy.severityCodes }?.let { severity ->
                AiClassification(
                    summary = content.description.orEmpty().replace(Regex("\\s+"), " ").trim().take(200)
                        .ifBlank { content.title.orEmpty().take(200) },
                    category = category, severity = severity,
                    sentiment = content.userSentiment?.takeIf { it in setOf("POSITIVE", "NEUTRAL", "NEGATIVE") } ?: "NEUTRAL",
                    confidence = java.math.BigDecimal("0.990000"),
                    explanation = "来源标准字段完整，按级联路由直接采用来源事实",
                    evidenceCodes = listOf("SOURCE_CATEGORY", "SOURCE_SEVERITY", "CASCADE_SOURCE_FIRST"),
                    modelVersion = "source-field-v1", degraded = false
                )
            }
        }
        val ruleResult = sourceResult ?: rules.classify(content.title, content.description.orEmpty(), degraded = forceFallback)
        val prompt = skillPrompts?.version("feedback_classify", aiRequired(task.promptVersion))
        val reviewThreshold=runtimeConfig?.decimal(AiReviewService.REVIEW_THRESHOLD_KEY,AiReviewService.DEFAULT_REVIEW_THRESHOLD)?:AiReviewService.DEFAULT_REVIEW_THRESHOLD
        val usedLlm = sourceResult == null && !forceFallback && (ruleResult.category == "CONSULT" || ruleResult.confidence < reviewThreshold)
        val rawResult = when {
            sourceResult != null -> sourceResult
            forceFallback -> ruleResult
            ruleResult.category != "CONSULT" && ruleResult.confidence >= reviewThreshold -> ruleResult
            else -> { outboundPrivacy?.requireSafe("LLM_CLASSIFICATION",sourceId,content.title,content.description)
                if (prompt == null) llm.classify(content.title, content.description.orEmpty(), source.sourceChannel)
                    else llm.classify(content.title, content.description.orEmpty(), source.sourceChannel, channelContext(sourceId, source.sourceChannel), prompt) }
        }
        val result=enrich(rawResult,source,content)
        safetyGuard?.requireSafeOutput(result.summary,result.explanation,result.application,result.functionModule,result.recommendedTeam)
        outboundPrivacy?.requireSafe("AI_CLASSIFICATION_OUTPUT",sourceId,result.summary,result.explanation,result.application,result.functionModule,result.recommendedTeam)
        if (usedLlm && prompt != null) runShadow(sourceId, content, source.sourceChannel, prompt, result)
        val review = if (result.confidence < reviewThreshold || result.severity in setOf("S0", "S1")) "PENDING" else "NOT_REQUIRED"
        results.save(FeedbackAi(
            sourceId = sourceId, aiSummary = result.summary,aiApplication=result.application,aiFunctionModule=result.functionModule,
            aiCategory = result.category,aiSentiment=result.sentiment,aiRiskType=result.riskType,
            aiSeverity = result.severity, aiConfidence = result.confidence,
            aiExplanation = result.explanation,recommendedTeam=result.recommendedTeam,
            evidenceCodes = result.evidenceCodes.joinToString(",").take(500),dataSources=mapper.writeValueAsString(result.dataSources),
            aiProcessedAt = LocalDateTime.now(), aiModelVersion = result.modelVersion,
            classificationPromptVersion = task.promptVersion, classificationModelVersion = result.modelVersion,
            classificationTaxonomyVersion = AiClassificationTaxonomy.VERSION,
            reviewStatus = review, textFingerprint = simHash64(content.title + " " + content.description),
            vectorIndexStatus = "PENDING", searchIndexStatus = "PENDING"
        ))
        // 只有无需人工复核的结果才会成为规范事实。
        // 保留受治理的来源字段，只补填缺失的值。
        if (review == "NOT_REQUIRED" && (content.issueCategory == null || content.severityLevel == null)) {
            contents.save(content.copy(
                issueCategory = content.issueCategory ?: result.category,
                severityLevel = content.severityLevel ?: result.severity,
                updatedAt = LocalDateTime.now()
            ))
        }
        val outputSnapshot = mapper.writeValueAsString(mapOf(
            "application" to result.application,"functionModule" to result.functionModule,"category" to result.category,
            "severity" to result.severity, "sentiment" to result.sentiment,"riskType" to result.riskType,
            "recommendedTeam" to result.recommendedTeam,"dataSources" to result.dataSources,
            "confidence" to result.confidence, "degraded" to result.degraded,
            "taxonomyVersion" to AiClassificationTaxonomy.VERSION,
            "severityPolicyVersion" to (severityPolicy?.version() ?: SeverityPolicy.VERSION)
        ))
        val leaseHolder = task.leaseHolder
        if (persistence != null && !leaseHolder.isNullOrBlank()) {
            // 写回必须带上本次领取的 generation：leaseHolder 可能被复用，只有 generation 才能证明这还是本次租约。
            persistence.complete(task.id, leaseHolder, task.leaseGeneration, outputSnapshot,
                if (result.degraded) "AI_DEGRADED" else null)
        } else {
            task.taskStatus = "COMPLETED"
            task.outputSnapshot = outputSnapshot
            task.errorDetail = if (result.degraded) "AI_DEGRADED" else null
            task.completedAt = LocalDateTime.now()
            task.leaseHolder = null; task.leaseExpiresAt = null; task.nextRetryAt = null; task.updatedAt = LocalDateTime.now()
            tasks.save(task)
        }
        if (prompt != null) persistence?.updatePromptSnapshot(AiPromptSnapshotUpdatePo(
            task.id, prompt.promptVersionId, prompt.skillVersion, prompt.contentSha256,
            mapper.writeValueAsString(prompt.modelConfig),
            skillPrompts!!.invocationSnapshot(prompt)["applicationVersion"]?.toString(),
            result.latencyMs, result.inputTokens, result.outputTokens, result.estimatedCostUsd,
            result.providerRequestId, result.modelVersion
        ))
        return result
    }

    private fun enrich(result:AiClassification,source:com.feedback.model.feedback.FeedbackSource,content:com.feedback.model.feedback.FeedbackContent):AiClassification{
        val application=result.application?.takeIf(String::isNotBlank)?:source.packageName?.takeIf(String::isNotBlank)?:"${source.sourceChannel}_APPLICATION"
        val module=result.functionModule?.takeIf(String::isNotBlank)?:content.functionModule?.takeIf(String::isNotBlank)?:"UNKNOWN"
        val risk=result.riskType.takeIf{it in RISK_TYPES&&it!="NONE"}?:when{
            result.category=="SECURITY"->"DEVICE_SECURITY"
            result.severity in setOf("S0","S1")->"OPERATIONAL_HIGH_RISK"
            result.sentiment=="NEGATIVE"&&source.sourceChannel=="REDBLACK"->"REPUTATION"
            else->"NONE"
        }
        val team=result.recommendedTeam?.takeIf(String::isNotBlank)?.take(50)?:when(result.category){
            "SECURITY"->"安全与隐私团队";"BUG"->"产品研发团队";"CONTENT_ERROR"->"内容质量团队";else->"产品运营团队"
        }
        return result.copy(application=application.take(100),functionModule=module.take(100),riskType=risk,
            recommendedTeam=team,dataSources=listOf(source.sourceChannel).distinct())
    }

    private fun simHash64(value: String): Long {
        val weights = IntArray(64)
        Regex("[\\p{L}\\p{N}_]+").findAll(value.lowercase()).map { it.value }.forEach { token ->
            val hash = token.fold(0xcbf29ce484222325UL) { acc, char -> (acc xor char.code.toULong()) * 0x100000001b3UL }
            repeat(64) { bit -> weights[bit] += if (((hash shr bit) and 1UL) == 1UL) 1 else -1 }
        }
        var result = 0UL
        repeat(64) { if (weights[it] >= 0) result = result or (1UL shl it) }
        return result.toLong()
    }

    private fun channelContext(sourceId: Long, channel: String): Map<String, Any?> {
        return persistence?.channelContext(sourceId, channel) ?: emptyMap()
    }

    private fun runShadow(sourceId: Long, content: com.feedback.model.feedback.FeedbackContent, channel: String,
                          primary: RenderedPrompt, primaryResult: AiClassification) {
        val shadow = skillPrompts?.shadowForInvocation("feedback_classify", sourceId.toString(), primary.promptVersionId) ?: return
        val shadowResult = runCatching { llm.classify(content.title, content.description.orEmpty(), channel,
            channelContext(sourceId, channel), shadow) }.getOrNull() ?: return
        persistence?.insertShadow(AiShadowEvaluationInsertPo(
            shadow.promptVersion, shadow.promptVersionId, sourceId,
            mapper.writeValueAsString(mapOf("mode" to "SHADOW", "primaryVersion" to primary.promptVersion)),
            mapper.writeValueAsString(mapOf("primaryCategory" to primaryResult.category,
                "shadowCategory" to shadowResult.category, "primarySeverity" to primaryResult.severity,
                "shadowSeverity" to shadowResult.severity,
                "same" to (primaryResult.category == shadowResult.category && primaryResult.severity == shadowResult.severity))),
            shadowResult.latencyMs, shadowResult.inputTokens, shadowResult.outputTokens,
            shadowResult.estimatedCostUsd, shadowResult.providerRequestId, shadow.skillVersion, shadow.contentSha256
        ))
    }

    companion object {
        private val RISK_TYPES=setOf("NONE","PRIVACY","DEVICE_SECURITY","CONTENT_SAFETY","REPUTATION","OPERATIONAL_HIGH_RISK")
    }
}

@Component
class AiEnqueueScheduler(
    private val repository: AiPipelineRepository,
    private val service: AiTaskService,
    @Value("\${app.ai.feedback-classify-enabled:false}")
    private val enabled: Boolean = false
) {
    fun enqueuePassRecords() {
        if (!enabled) return
        repository.enqueueSourceIds(100).forEach(service::enqueue)
    }
}

@Component
class AiWorker(private val claims: AiTaskClaimService, private val processor: AiTaskProcessor) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val workerId = "ai-${UUID.randomUUID()}"

    fun runPending() {
        repeat(10) {
            val task = claims.claim(workerId) ?: return
            runCatching { processor.process(task.id, forceFallback = task.retryCount >= 2) }
                .onFailure { error ->
                    log.warn("AI task failed task_id={} code={}", task.id, error.javaClass.simpleName)
                    claims.retry(task.id, aiRequired(task.leaseHolder), task.leaseGeneration, error)
                }
        }
    }
}

@Component
class AiWorkerScheduler(
    private val worker: AiWorker,
    @Value("\${app.ai.feedback-classify-enabled:false}")
    private val enabled: Boolean = false
) {
    fun runPending() {
        if (!enabled) return
        worker.runPending()
    }
}
