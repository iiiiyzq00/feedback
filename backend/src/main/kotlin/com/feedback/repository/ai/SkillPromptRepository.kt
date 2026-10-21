package com.feedback.repository.ai

import com.feedback.model.po.ai.PromptVersionInsertPo
import com.feedback.mapper.ai.PromptFeedbackStatsMapper
import com.feedback.model.po.ai.SkillDefinitionInsertPo
import com.feedback.mapper.ai.SkillPromptMapper
import org.springframework.stereotype.Repository

/** 存放在 t_ai_task 里的受治理 Skill 与 Prompt 版本的持久化边界（单表）。 */
@Repository
class SkillPromptRepository(
    private val mapper: SkillPromptMapper,
    private val feedbackStats: PromptFeedbackStatsMapper,
) {
    fun promptByVersion(skill: String, version: String) = mapper.promptByVersion(skill, version)
    fun latestPublishedSkillVersion(skill: String) = mapper.latestPublishedSkillVersion(skill)
    fun hasCurrentPrompt(skill: String) = mapper.currentPromptCount(skill) > 0
    fun insertPrompt(row: PromptVersionInsertPo): Long {
        ensureAiRepositoryState(mapper.insertPrompt(row) == 1) { "PROMPT_INSERT_FAILED" }
        return aiRepositoryStateRequired(row.id) { "PROMPT_INSERT_ID_MISSING" }
    }
    fun updateBootstrapBaseline(id: Long, template: String, model: String, sha: String) =
        ensureAiRepositoryState(mapper.updateBootstrapBaseline(id, template, model, sha) == 1) { "PROMPT_BOOTSTRAP_UPDATE_FAILED" }
    fun currentSkillCodes() = mapper.currentSkillCodes().toSet()
    fun listSkills() = mapper.listSkills()
    fun listPrompts(skill: String) = mapper.listPrompts(skill)
    fun invocationStats(skill: String, days: Int) = mapper.invocationStats(skill, days)
    fun feedbackStats(versions: Collection<String>) = if (versions.isEmpty()) emptyList() else feedbackStats.stats(versions)
    fun insertSkill(row: SkillDefinitionInsertPo): Long {
        ensureAiRepositoryState(mapper.insertSkill(row) == 1) { "SKILL_INSERT_FAILED" }
        return aiRepositoryStateRequired(row.id) { "SKILL_INSERT_ID_MISSING" }
    }
    fun currentSkill(skill: String) = mapper.currentSkill(skill)
    fun markSkillTested(id: Long) = ensureAiRepositoryState(mapper.markSkillTested(id) == 1) { "SKILL_TEST_UPDATE_FAILED" }
    fun assetForApproval(id: Long) = mapper.assetForApproval(id)
    fun approve(id: Long, actor: Long?) = ensureAiRepositoryState(mapper.approve(id, actor) == 1) { "AI_ASSET_APPROVE_FAILED" }
    fun retireCurrentSkill(skill: String) = mapper.retireCurrentSkill(skill)
    fun publishSkill(id: Long, actor: Long?) = ensureAiRepositoryState(mapper.publishSkill(id, actor) == 1) { "SKILL_PUBLISH_FAILED" }
    fun rollbackSkill(skill: String, version: String) = mapper.rollbackSkill(skill, version)
    fun retireSkill(id: Long) = ensureAiRepositoryState(mapper.retireSkill(id) == 1) { "SKILL_RETIRE_FAILED" }
    fun prompt(id: Long) = mapper.prompt(id)
    fun writeStructuralEvaluation(id: Long, summary: String) = mapper.writeStructuralEvaluation(id, summary)
    fun currentPromptIdentity(skill: String) = mapper.currentPromptIdentity(skill)
    fun retireCurrentPrompt(skill: String) = mapper.retireCurrentPrompt(skill)
    fun publishPrompt(id: Long, actor: Long?, previous: Long?) =
        ensureAiRepositoryState(mapper.publishPrompt(id, actor, previous) == 1) { "PROMPT_PUBLISH_FAILED" }
    fun rollbackPromptId(skill: String, version: String) = mapper.rollbackPromptId(skill, version)
    fun activateRollbackPrompt(id: Long, actor: Long?, previous: Long?) =
        ensureAiRepositoryState(mapper.activateRollbackPrompt(id, actor, previous) == 1) { "PROMPT_ROLLBACK_FAILED" }
    fun configureRollout(id: Long, rollout: Int, shadow: Int) = mapper.configureRollout(id, rollout, shadow)
    fun disableEmergencyPrompt(id: Long, reason: String) =
        ensureAiRepositoryState(mapper.disableEmergencyPrompt(id, reason) == 1) { "PROMPT_EMERGENCY_DISABLE_FAILED" }
    fun activateEmergencyPrevious(id: Long, actor: Long?) =
        ensureAiRepositoryState(mapper.activateEmergencyPrevious(id, actor) == 1) { "PROMPT_EMERGENCY_ACTIVATE_FAILED" }
    fun currentPromptSelection(skill: String) = mapper.currentPromptSelection(skill)
    fun currentPromptRows(skill: String) = mapper.currentPromptRows(skill)
    fun versionPromptRows(skill: String, version: String) = mapper.versionPromptRows(skill, version)
    fun publishedPromptById(id: Long) = mapper.publishedPromptById(id)
    fun backfillSha(id: Long, sha: String) = ensureAiRepositoryState(mapper.backfillSha(id, sha) == 1) { "PROMPT_SHA_BACKFILL_FAILED" }
    fun skillExists(skill: String) = mapper.skillCount(skill) > 0
}
