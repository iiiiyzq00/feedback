package com.feedback.mapper.ai

import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper
interface SkillPromptMapper {
    fun promptByVersion(@Param("skill") skill: String, @Param("version") version: String): SkillPromptAssetPo?
    fun latestPublishedSkillVersion(@Param("skill") skill: String): String?
    fun currentPromptCount(@Param("skill") skill: String): Long
    fun insertPrompt(@Param("row") row: PromptVersionInsertPo): Int
    fun updateBootstrapBaseline(@Param("id") id: Long, @Param("template") template: String,
                                @Param("model") model: String, @Param("sha") sha: String): Int
    fun currentSkillCodes(): List<String>
    fun listSkills(): List<SkillPromptAssetPo>
    fun listPrompts(@Param("skill") skill: String): List<SkillPromptAssetPo>
    fun invocationStats(@Param("skill") skill: String, @Param("days") days: Int): List<SkillPromptInvocationStatsPo>
    fun insertSkill(@Param("row") row: SkillDefinitionInsertPo): Int
    fun currentSkill(@Param("skill") skill: String): SkillPromptAssetPo?
    fun markSkillTested(@Param("id") id: Long): Int
    fun assetForApproval(@Param("id") id: Long): SkillPromptAssetPo?
    fun approve(@Param("id") id: Long, @Param("actor") actor: Long?): Int
    fun retireCurrentSkill(@Param("skill") skill: String): Int
    fun publishSkill(@Param("id") id: Long, @Param("actor") actor: Long?): Int
    fun rollbackSkill(@Param("skill") skill: String, @Param("version") version: String): SkillPromptAssetPo?
    fun retireSkill(@Param("id") id: Long): Int
    fun prompt(@Param("id") id: Long): SkillPromptAssetPo?
    fun writeStructuralEvaluation(@Param("id") id: Long, @Param("summary") summary: String): Int
    fun currentPromptIdentity(@Param("skill") skill: String): SkillPromptAssetPo?
    fun retireCurrentPrompt(@Param("skill") skill: String): Int
    fun publishPrompt(@Param("id") id: Long, @Param("actor") actor: Long?, @Param("previous") previous: Long?): Int
    fun rollbackPromptId(@Param("skill") skill: String, @Param("version") version: String): Long?
    fun activateRollbackPrompt(@Param("id") id: Long, @Param("actor") actor: Long?, @Param("previous") previous: Long?): Int
    fun configureRollout(@Param("id") id: Long, @Param("rollout") rollout: Int, @Param("shadow") shadow: Int): Int
    fun disableEmergencyPrompt(@Param("id") id: Long, @Param("reason") reason: String): Int
    fun activateEmergencyPrevious(@Param("id") id: Long, @Param("actor") actor: Long?): Int
    fun currentPromptSelection(@Param("skill") skill: String): SkillPromptAssetPo?
    fun currentPromptRows(@Param("skill") skill: String): List<SkillPromptAssetPo>
    fun versionPromptRows(@Param("skill") skill: String, @Param("version") version: String): List<SkillPromptAssetPo>
    fun publishedPromptById(@Param("id") id: Long): SkillPromptAssetPo?
    fun backfillSha(@Param("id") id: Long, @Param("sha") sha: String): Int
    fun skillCount(@Param("skill") skill: String): Long
}

