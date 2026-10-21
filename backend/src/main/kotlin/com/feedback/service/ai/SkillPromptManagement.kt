package com.feedback.service.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.audit.SystemAuditLog
import com.feedback.model.po.ai.PromptVersionInsertPo
import com.feedback.model.po.ai.SkillDefinitionInsertPo
import com.feedback.model.po.ai.SkillPromptAssetPo
import com.feedback.repository.SystemAuditLogRepository
import com.feedback.repository.ai.SkillPromptRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.LocalDateTime

data class RenderedPrompt(
    val skillCode: String,
    val skillVersion: String,
    val promptVersion: String,
    val promptVersionId: Long,
    val template: String,
    val contentSha256: String,
    val modelConfig: JsonNode,
)

data class CreateSkillRequest(
    val skillCode: String,
    val skillName: String,
    val skillVersion: String,
    val inputSchema: JsonNode,
    val outputSchema: JsonNode,
    val riskLevel: String,
)

data class CreatePromptRequest(
    val promptVersion: String,
    val template: String,
    val modelConfig: JsonNode,
    val releaseNotes: String? = null,
)

data class PromptRegressionCase(val name: String, val variables: Map<String, Any?>)

/**
 * 治理 Skill 与 Prompt 的完整生命周期，同时把所有数据库访问收拢在
 * 单表 MyBatis 仓储里。发布、放量、完整性、评测与回滚这几条
 * 不变式刻意与原实现保持一致。
 */
@Service
class SkillPromptService(
    private val repository: SkillPromptRepository,
    private val mapper: ObjectMapper,
    private val audits: SystemAuditLogRepository,
    @Value("\${spring.application.version:dev}") private val applicationVersion: String,
    @Value("\${app.ai.require-two-person-approval:true}") private val requireTwoPerson: Boolean,
    @Value("\${app.ai.prompt-assets-path:prompts}") private val promptAssetsPath: String,
    @Value("\${app.ai.require-evaluation-for-invocation:false}") private val requireEvaluation: Boolean,
) {
    @Transactional
    fun bootstrapAsset(asset: PromptAsset, template: String) {
        ensureAi(asset.version.matches(SEMVER) && asset.modelConfig.isObject) { "PROMPT_ASSET_METADATA_INVALID" }
        validateModelConfig(asset.modelConfig)
        val existing = repository.promptByVersion(asset.skillCode, asset.version)
        if (existing != null) {
            if (existing.contentSha256 != asset.sha256 && existing.releaseNotes == "V16 baseline") {
                repository.updateBootstrapBaseline(
                    existing.id,
                    template,
                    mapper.writeValueAsString(asset.modelConfig),
                    asset.sha256,
                )
            } else {
                ensureAi(existing.contentSha256 == asset.sha256) {
                    "PROMPT_DATABASE_ASSET_DRIFT:${asset.skillCode}:${asset.version}"
                }
            }
            return
        }
        val skillVersion = repository.latestPublishedSkillVersion(asset.skillCode)
            ?: aiAbort("PROMPT_ASSET_SKILL_NOT_FOUND:${asset.skillCode}")
        val initial = !repository.hasCurrentPrompt(asset.skillCode)
        repository.insertPrompt(
            PromptVersionInsertPo(
                skillCode = asset.skillCode,
                promptVersion = asset.version,
                promptTemplate = template,
                modelConfig = mapper.writeValueAsString(asset.modelConfig),
                isCurrent = initial,
                lifecycleStatus = if (initial) "PUBLISHED" else "DRAFT",
                approvalStatus = if (initial) "APPROVED" else "PENDING",
                contentSha256 = asset.sha256,
                releaseNotes = "Git manifest bootstrap",
                skillVersionSnapshot = skillVersion,
                bootstrap = true,
            )
        )
    }

    fun requireCurrentPrompts(skills: Set<String>) {
        val current = repository.currentSkillCodes()
        ensureAi(current.containsAll(skills)) {
            "CURRENT_PROMPT_MISSING:${(skills - current).sorted().joinToString(",")}"
        }
    }

    fun listSkills(): List<Map<String, Any?>> = repository.listSkills().map { row ->
        linkedMapOf(
            "id" to row.id,
            "skillCode" to row.skillCode,
            "skillName" to row.skillName,
            "skillVersion" to row.skillVersion,
            "riskLevel" to row.riskLevel,
            "lifecycleStatus" to row.lifecycleStatus,
            "inputSchema" to row.inputSchema,
            "outputSchema" to row.outputSchema,
            "createdAt" to row.createdAt,
        )
    }

    fun listPrompts(skillCode: String): List<Map<String, Any?>> = repository.listPrompts(skillCode).map { row ->
        linkedMapOf(
            "id" to row.id,
            "skillCode" to row.skillCode,
            "promptVersion" to row.promptVersion,
            "lifecycleStatus" to row.lifecycleStatus,
            "isCurrent" to row.isCurrent,
            "contentSha256" to row.contentSha256,
            "modelConfig" to row.modelConfig,
            "releaseNotes" to row.releaseNotes,
            "publishedAt" to row.publishedAt,
            "createdAt" to row.createdAt,
        )
    }

    fun stats(skillCode: String, days: Int): List<Map<String, Any?>> {
        ensureAi(days in 1..365) { "STATS_DAYS_INVALID" }
        val taskStats = repository.invocationStats(skillCode, days)
        val feedback = repository.feedbackStats(taskStats.map { it.promptVersion })
            .associateBy { it.promptVersion }
        return taskStats.map { row ->
            linkedMapOf(
                "promptVersion" to row.promptVersion,
                "invocationCount" to row.invocationCount,
                "successCount" to row.successCount,
                "failureCount" to row.failureCount,
                "avgLatencyMs" to row.avgLatencyMs,
                "p50LatencyMs" to row.p50LatencyMs,
                "p95LatencyMs" to row.p95LatencyMs,
                "p99LatencyMs" to row.p99LatencyMs,
                "inputTokens" to row.inputTokens,
                "outputTokens" to row.outputTokens,
                "estimatedCostUsd" to row.estimatedCostUsd,
                "avgConfidence" to feedback[row.promptVersion]?.avgConfidence,
                "correctionRate" to feedback[row.promptVersion]?.correctionRate,
            )
        }
    }

    @Transactional
    fun createSkill(request: CreateSkillRequest, actor: Long?): Long {
        ensureAi(request.skillCode.matches(Regex("[a-z][a-z0-9_]{2,49}"))) { "SKILL_CODE_INVALID" }
        ensureAi(request.skillVersion.matches(SEMVER)) { "SKILL_VERSION_INVALID" }
        ensureAi(request.riskLevel in setOf("A", "B", "C")) { "SKILL_RISK_INVALID" }
        validateObjectSchema(request.inputSchema)
        validateObjectSchema(request.outputSchema)
        val id = repository.insertSkill(
            SkillDefinitionInsertPo(
                skillCode = request.skillCode,
                skillName = request.skillName.trim().take(100),
                skillVersion = request.skillVersion,
                inputSchema = mapper.writeValueAsString(request.inputSchema),
                outputSchema = mapper.writeValueAsString(request.outputSchema),
                riskLevel = request.riskLevel,
                createdBy = actor,
            )
        )
        audit("SKILL_CREATE", "AI_SKILL", id.toString(), actor, mapOf(
            "skillCode" to request.skillCode,
            "version" to request.skillVersion,
        ))
        return id
    }

    @Transactional
    fun testSkill(id: Long, actor: Long?): Map<String, Any> {
        val skill = skillRow(id)
        ensureAi(skill.lifecycleStatus == "DRAFT") { "SKILL_NOT_DRAFT" }
        val current = repository.currentSkill(skill.skillCode)
        if (current != null && major(aiRequired(skill.skillVersion)) == major(aiRequired(current.skillVersion))) {
            ensureAi(schemaBackwardCompatible(readJson(current.inputSchema), readJson(skill.inputSchema))) {
                "SKILL_INPUT_SCHEMA_BREAKING_CHANGE_REQUIRES_MAJOR"
            }
            ensureAi(schemaBackwardCompatible(readJson(skill.outputSchema), readJson(current.outputSchema))) {
                "SKILL_OUTPUT_SCHEMA_BREAKING_CHANGE_REQUIRES_MAJOR"
            }
        }
        repository.markSkillTested(id)
        audit("SKILL_TEST", "AI_SKILL", id.toString(), actor, mapOf("backwardCompatible" to true))
        return mapOf("tested" to true, "backwardCompatible" to true)
    }

    @Transactional
    fun approve(id: Long, actor: Long?): Map<String, Any> {
        val row = repository.assetForApproval(id) ?: aiNotFound("AI_ASSET_NOT_FOUND")
        ensureAi(row.lifecycleStatus == "TESTED" && row.approvalStatus == "PENDING") {
            "AI_ASSET_NOT_AWAITING_APPROVAL"
        }
        ensureAi(AiGovernancePolicy.validSecondApprover(row.createdBy, actor, requireTwoPerson)) {
            "SECOND_APPROVER_REQUIRED"
        }
        repository.approve(id, actor)
        audit("AI_ASSET_APPROVE", row.recordType, id.toString(), actor, emptyMap())
        return mapOf("approved" to true)
    }

    @Transactional
    fun publishSkill(id: Long, actor: Long?): Map<String, Any?> {
        val row = skillRow(id)
        ensureAi(row.lifecycleStatus == "TESTED" && row.approvalStatus == "APPROVED") { "SKILL_NOT_APPROVED" }
        repository.retireCurrentSkill(row.skillCode)
        repository.publishSkill(id, actor)
        audit("SKILL_PUBLISH", "AI_SKILL", id.toString(), actor, mapOf(
            "skillCode" to row.skillCode,
            "version" to row.skillVersion,
        ))
        return mapOf("id" to id, "skillCode" to row.skillCode, "skillVersion" to row.skillVersion, "published" to true)
    }

    @Transactional
    fun rollbackSkill(skillCode: String, version: String, actor: Long?): Map<String, Any?> {
        val row = repository.rollbackSkill(skillCode, version)
            ?: aiNotFound("SKILL_VERSION_NOT_FOUND")
        repository.retireCurrentSkill(skillCode)
        repository.publishSkill(row.id, actor)
        audit("SKILL_ROLLBACK", "AI_SKILL", row.id.toString(), actor, mapOf(
            "skillCode" to skillCode,
            "version" to version,
        ))
        return mapOf("skillCode" to skillCode, "skillVersion" to version, "rolledBack" to true)
    }

    @Transactional
    fun retireSkill(id: Long, actor: Long?): Map<String, Any> {
        val row = skillRow(id)
        ensureAi(!row.isCurrent) { "CURRENT_SKILL_REPLACEMENT_REQUIRED" }
        ensureAi(row.lifecycleStatus in setOf("DRAFT", "TESTED", "PUBLISHED")) { "SKILL_NOT_RETIRABLE" }
        repository.retireSkill(id)
        audit("SKILL_RETIRE", "AI_SKILL", id.toString(), actor, mapOf(
            "skillCode" to row.skillCode,
            "version" to row.skillVersion,
        ))
        return mapOf("retired" to true)
    }

    @Transactional
    fun createPrompt(skillCode: String, request: CreatePromptRequest, actor: Long?): Long {
        ensureAi(request.promptVersion.matches(SEMVER)) { "PROMPT_VERSION_INVALID" }
        ensureAi(request.template.length in 20..20_000) { "PROMPT_TEMPLATE_INVALID" }
        ensureAi(request.modelConfig.isObject) { "MODEL_CONFIG_INVALID" }
        validateModelConfig(request.modelConfig)
        verifyGitAsset(skillCode, request)
        ensureAi(repository.skillExists(skillCode)) { "SKILL_NOT_FOUND" }
        val skillVersion = repository.latestPublishedSkillVersion(skillCode)
            ?: aiInvalid("PUBLISHED_SKILL_NOT_FOUND")
        val sha = sha256(request.template)
        val id = repository.insertPrompt(
            PromptVersionInsertPo(
                skillCode = skillCode,
                promptVersion = request.promptVersion,
                promptTemplate = request.template,
                modelConfig = mapper.writeValueAsString(request.modelConfig),
                contentSha256 = sha,
                releaseNotes = request.releaseNotes?.take(500),
                createdBy = actor,
                skillVersionSnapshot = skillVersion,
            )
        )
        audit("PROMPT_CREATE", "AI_PROMPT", id.toString(), actor, mapOf(
            "skillCode" to skillCode,
            "version" to request.promptVersion,
            "sha256" to sha,
        ))
        return id
    }

    @Transactional
    fun markTested(id: Long, cases: List<PromptRegressionCase>, actor: Long?): Map<String, Any> {
        ensureAi(cases.isNotEmpty()) { "PROMPT_REGRESSION_CASE_REQUIRED" }
        val row = promptRow(id)
        val template = aiRequired(row.promptTemplate)
        ensureAi(Regex("忽略|不得|不可信|只能").containsMatchIn(template)) { "PROMPT_SECURITY_GUARD_REQUIRED" }
        if (row.skillCode == "feedback_classify") {
            ensureAi(listOf("BUG", "HARDWARE", "confidence", "JSON").all { it in template }) {
                "CLASSIFY_PROMPT_CONTRACT_MISSING"
            }
        }
        cases.forEach { case ->
            ensureAi(case.name.isNotBlank()) { "PROMPT_CASE_NAME_REQUIRED" }
            PromptRenderer.render(template, case.variables)
        }
        val structural = mapper.writeValueAsString(mapOf(
            "structuralGate" to "PASSED",
            "caseCount" to cases.size,
            "testedAt" to LocalDateTime.now().toString(),
        ))
        ensureAi(repository.writeStructuralEvaluation(id, structural) == 1) { "PROMPT_NOT_DRAFT" }
        audit("PROMPT_TEST", "AI_PROMPT", id.toString(), actor, mapOf("caseCount" to cases.size))
        return mapOf("structuralTested" to true, "caseCount" to cases.size, "modelEvaluationRequired" to true)
    }

    @Transactional
    fun publish(id: Long, actor: Long?): RenderedPrompt {
        val row = promptRow(id)
        ensureAi(row.lifecycleStatus == "TESTED" && row.approvalStatus == "APPROVED") { "PROMPT_NOT_APPROVED" }
        val previousId = repository.currentPromptIdentity(row.skillCode)?.id
        repository.retireCurrentPrompt(row.skillCode)
        repository.publishPrompt(id, actor, previousId)
        audit("PROMPT_PUBLISH", "AI_PROMPT", id.toString(), actor, mapOf(
            "skillCode" to row.skillCode,
            "version" to row.promptVersion,
        ))
        return current(row.skillCode)
    }

    @Transactional
    fun rollback(skillCode: String, promptVersion: String, actor: Long?): RenderedPrompt {
        val id = repository.rollbackPromptId(skillCode, promptVersion)
            ?: aiNotFound("PROMPT_VERSION_NOT_FOUND")
        val previousId = repository.currentPromptIdentity(skillCode)?.id
        repository.retireCurrentPrompt(skillCode)
        repository.activateRollbackPrompt(id, actor, previousId)
        audit("PROMPT_ROLLBACK", "AI_PROMPT", id.toString(), actor, mapOf(
            "skillCode" to skillCode,
            "version" to promptVersion,
        ))
        return current(skillCode)
    }

    @Transactional
    fun configureRollout(id: Long, rolloutPercent: Int, shadowPercent: Int, actor: Long?): Map<String, Any> {
        ensureAi(rolloutPercent in 0..100 && shadowPercent in 0..100) { "ROLLOUT_PERCENT_INVALID" }
        ensureAi(repository.configureRollout(id, rolloutPercent, shadowPercent) == 1) { "CURRENT_PROMPT_NOT_FOUND" }
        audit("PROMPT_ROLLOUT_CONFIG", "AI_PROMPT", id.toString(), actor, mapOf(
            "rolloutPercent" to rolloutPercent,
            "shadowPercent" to shadowPercent,
        ))
        return mapOf("rolloutPercent" to rolloutPercent, "shadowPercent" to shadowPercent)
    }

    @Transactional
    fun emergencyRollback(skillCode: String, actor: Long?, reason: String): RenderedPrompt {
        val current = repository.currentPromptIdentity(skillCode)
            ?: aiNotFound("CURRENT_PROMPT_NOT_FOUND")
        val previous = current.previousPromptVersionId
            ?: aiFailed("PROMPT_ROLLBACK_TARGET_MISSING")
        repository.disableEmergencyPrompt(current.id, reason.take(200))
        repository.activateEmergencyPrevious(previous, actor)
        audit("PROMPT_EMERGENCY_ROLLBACK", "AI_PROMPT", current.id.toString(), actor, mapOf(
            "skillCode" to skillCode,
            "reason" to reason,
        ))
        return current(skillCode)
    }

    fun selectForInvocation(skillCode: String, stableKey: String): RenderedPrompt {
        val row = repository.currentPromptSelection(skillCode)
            ?: aiFailed("CURRENT_PROMPT_NOT_UNIQUE")
        val targetId = if (AiGovernancePolicy.usesCandidate(stableKey, row.rolloutPercent)) {
            row.id
        } else {
            row.previousPromptVersionId ?: row.id
        }
        return byId(skillCode, targetId)
    }

    fun shadowForInvocation(skillCode: String, stableKey: String, selectedPromptId: Long): RenderedPrompt? {
        val row = repository.currentPromptIdentity(skillCode) ?: return null
        if (!AiGovernancePolicy.usesShadow(stableKey, row.shadowPercent)) return null
        val previousId = row.previousPromptVersionId ?: return null
        return byId(skillCode, if (selectedPromptId == row.id) previousId else row.id)
    }

    fun current(skillCode: String): RenderedPrompt {
        val rows = repository.currentPromptRows(skillCode)
        ensureAi(rows.size == 1) { "CURRENT_PROMPT_NOT_UNIQUE" }
        return toRendered(skillCode, rows.single())
    }

    fun version(skillCode: String, promptVersion: String): RenderedPrompt {
        val rows = repository.versionPromptRows(skillCode, promptVersion)
        ensureAi(rows.size == 1) { "PROMPT_VERSION_NOT_UNIQUE" }
        return toRendered(skillCode, rows.single())
    }

    fun candidate(id: Long): RenderedPrompt {
        val row = promptRow(id)
        ensureAi(row.lifecycleStatus in setOf("DRAFT", "TESTED", "PUBLISHED")) { "PROMPT_NOT_EVALUATABLE" }
        return toRendered(row.skillCode, row, enforceEvaluationGate = false)
    }

    fun invocationSnapshot(prompt: RenderedPrompt) = mapOf(
        "promptVersionId" to prompt.promptVersionId,
        "skillVersion" to prompt.skillVersion,
        "promptSha256" to prompt.contentSha256,
        "modelConfig" to mapper.writeValueAsString(prompt.modelConfig),
        "applicationVersion" to applicationVersion,
    )

    private fun toRendered(
        skillCode: String,
        row: SkillPromptAssetPo,
        enforceEvaluationGate: Boolean = true,
    ): RenderedPrompt {
        val template = aiRequired(row.promptTemplate) { "PROMPT_TEMPLATE_MISSING" }
        val actual = sha256(template)
        row.contentSha256?.let { ensureAi(it == actual) { "PROMPT_INTEGRITY_FAILED" } }
            ?: repository.backfillSha(row.id, actual)
        if (requireEvaluation && enforceEvaluationGate) {
            val evaluation = row.evaluationSummary?.let(mapper::readTree)
            ensureAi(evaluation?.path("modelGate")?.asText() == "PASSED") {
                "PROMPT_MODEL_EVALUATION_REQUIRED:$skillCode:${row.promptVersion}"
            }
        }
        val skillVersion = row.skillVersionSnapshot
            ?: repository.latestPublishedSkillVersion(skillCode)
            ?: "unknown"
        return RenderedPrompt(
            skillCode = skillCode,
            skillVersion = skillVersion,
            promptVersion = aiRequired(row.promptVersion),
            promptVersionId = row.id,
            template = template,
            contentSha256 = actual,
            modelConfig = mapper.readTree(row.modelConfig ?: "{}"),
        )
    }

    private fun byId(skillCode: String, id: Long): RenderedPrompt {
        val row = repository.publishedPromptById(id) ?: aiNotFound("PROMPT_VERSION_NOT_FOUND")
        return toRendered(skillCode, row)
    }

    private fun promptRow(id: Long) = repository.prompt(id) ?: aiNotFound("PROMPT_NOT_FOUND")
    private fun skillRow(id: Long) = repository.assetForApproval(id)?.takeIf { it.recordType == "SKILL_DEF" }
        ?: aiNotFound("SKILL_NOT_FOUND")
    private fun readJson(value: String?) = mapper.readTree(aiRequired(value))
    private fun validateObjectSchema(schema: JsonNode) {
        ensureAi(schema.isObject && schema.path("type").asText() == "object") { "JSON_SCHEMA_INVALID" }
    }

    private fun validateModelConfig(config: JsonNode) {
        val allowed = setOf("temperature", "top_p", "max_tokens", "response_format")
        ensureAi(config.fieldNames().asSequence().all { it in allowed }) { "MODEL_CONFIG_FIELD_NOT_ALLOWED" }
        config.get("temperature")?.let {
            ensureAi(it.isNumber && it.asDouble() in 0.0..1.0) { "MODEL_TEMPERATURE_INVALID" }
        }
        config.get("top_p")?.let {
            ensureAi(it.isNumber && it.asDouble() > 0.0 && it.asDouble() <= 1.0) { "MODEL_TOP_P_INVALID" }
        }
        config.get("max_tokens")?.let {
            ensureAi(it.isInt && it.asInt() in 1..8192) { "MODEL_MAX_TOKENS_INVALID" }
        }
        config.get("response_format")?.let {
            ensureAi(it.asText() in setOf("json_object", "text")) { "MODEL_RESPONSE_FORMAT_INVALID" }
        }
    }

    private fun verifyGitAsset(skillCode: String, request: CreatePromptRequest) {
        val root = java.nio.file.Path.of(promptAssetsPath).toAbsolutePath().normalize()
        val manifestFile = root.resolve("manifest.json")
        ensureAi(java.nio.file.Files.isRegularFile(manifestFile)) { "PROMPT_GIT_MANIFEST_REQUIRED" }
        val manifest = mapper.readValue(java.nio.file.Files.readString(manifestFile), PromptAssetManifest::class.java)
        val asset = manifest.assets.singleOrNull {
            it.skillCode == skillCode && it.version == request.promptVersion
        } ?: aiInvalid("PROMPT_VERSION_NOT_IN_GIT_MANIFEST")
        val file = root.resolve(asset.file).normalize()
        ensureAi(file.startsWith(root) && java.nio.file.Files.isRegularFile(file)) { "PROMPT_GIT_ASSET_MISSING" }
        val content = java.nio.file.Files.readString(file)
        ensureAi(content == request.template && sha256(content) == asset.sha256) { "PROMPT_GIT_ASSET_MISMATCH" }
        ensureAi(asset.modelConfig == request.modelConfig) { "PROMPT_MODEL_CONFIG_MANIFEST_MISMATCH" }
    }

    private fun major(version: String) = version.substringBefore('.').toInt()

    private fun schemaBackwardCompatible(old: JsonNode, newer: JsonNode): Boolean {
        val oldRequired = old.path("required").map { it.asText() }.toSet()
        val newRequired = newer.path("required").map { it.asText() }.toSet()
        if (!oldRequired.containsAll(newRequired)) return false
        val oldProps = old.path("properties")
        val newProps = newer.path("properties")
        return oldProps.fieldNames().asSequence().all { name ->
            !newProps.has(name) || oldProps.path(name).path("type").asText() == newProps.path(name).path("type").asText()
        }
    }

    private fun audit(operation: String, type: String, id: String, actor: Long?, detail: Map<String, Any?>) =
        audits.save(
            SystemAuditLog(
                userId = actor,
                operation = operation,
                targetType = type,
                targetId = id,
                operationDetail = mapper.valueToTree(detail),
                ipAddress = (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                    as? org.springframework.web.context.request.ServletRequestAttributes)
                    ?.request?.remoteAddr?.take(50),
            )
        )

    companion object {
        private val SEMVER = Regex("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?")
        fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

object PromptRenderer {
    private val variable = Regex("\\{\\{([a-zA-Z][a-zA-Z0-9_]*)}}")

    fun render(template: String, variables: Map<String, Any?>): String {
        val required = variable.findAll(template).map { it.groupValues[1] }.toSet()
        val missing = required - variables.keys
        ensureAi(missing.isEmpty()) { "PROMPT_VARIABLE_MISSING:${missing.sorted().joinToString(",")}" }
        return variable.replace(template) { match ->
            val value = variables[match.groupValues[1]]?.toString().orEmpty()
            ensureAi(value.length <= 20_000) { "PROMPT_VARIABLE_TOO_LONG:${match.groupValues[1]}" }
            value
        }
    }
}
