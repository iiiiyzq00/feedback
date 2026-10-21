package com.feedback.mapper.ai

import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper
interface AiTaskMapper {
    fun findById(@Param("id") id: Long): AiTaskPo?
    fun findByTaskKey(@Param("taskKey") taskKey: String): AiTaskPo?
    fun insert(@Param("row") row: AiTaskInsertPo): Int
    fun update(@Param("row") row: AiTaskPo): Int
    fun findAvailabilityCandidates(@Param("afterId") afterId: Long, @Param("limit") limit: Int): List<AiTaskPo>
    fun lockClaimCandidates(@Param("limit") limit: Int): List<AiTaskPo>
    fun cancelUnavailable(@Param("ids") ids: List<Long>): Int
    fun claim(@Param("id") id: Long, @Param("holder") holder: String): Int
    fun terminalFailure(@Param("id") id: Long, @Param("holder") holder: String,
                        @Param("generation") generation: Long, @Param("code") code: String): Int
    fun retryFailure(@Param("id") id: Long, @Param("holder") holder: String,
                     @Param("generation") generation: Long, @Param("code") code: String): Int
    fun completeLease(@Param("id") id: Long, @Param("holder") holder: String, @Param("generation") generation: Long,
                      @Param("output") output: String, @Param("error") error: String?): Int
    fun updatePromptSnapshot(@Param("row") row: AiPromptSnapshotUpdatePo): Int
    fun insertShadow(@Param("row") row: AiShadowEvaluationInsertPo): Int
    fun insertNarrationShadow(@Param("row") row: AiNarrationShadowInsertPo): Int
    fun insertGovernedInvocation(@Param("row") row: GovernedSkillInvocationInsertPo): Int
    fun insertPromptEvaluation(@Param("row") row: PromptEvaluationInsertPo): Int
    fun updatePromptEvaluation(@Param("id") id: Long, @Param("passed") passed: Boolean,
                               @Param("summary") summary: String): Int
    fun findRollbackCandidates(): List<PromptRollbackCandidatePo>
    fun findRecentInvocationStats(): List<PromptInvocationStatsPo>
    fun findCurrentPromptVersion(@Param("skillCode") skillCode: String): String?
    fun findTaskKeys(@Param("taskKeys") taskKeys: List<String>): List<String>
    fun sumTodayGovernedTokens(): Long
}

