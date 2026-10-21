package com.feedback.repository.knowledge

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.knowledge.*
import com.feedback.model.po.knowledge.*
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

data class JiraKnowledgeCandidateFact(
    val sourceId: Long, val jiraKey: String, val title: String?, val description: String?,
    val module: String?, val issueType: String?, val resolution: String?, val fixVersion: String?,
    val commentSummary: String?, val resolvedAt: LocalDateTime?, val alreadyDrafted: Boolean
)
data class JiraKnowledgePreview(val eligible: Long, val missingEvidence: Long, val excludedResolution: Long, val alreadyDrafted: Long)

@Repository
class JiraKnowledgeCandidateRepository(
    private val sources: JiraKnowledgeSourceMapper,
    private val contents: JiraKnowledgeContentMapper,
    private val jira: JiraKnowledgeJiraMapper,
    private val drafts: JiraKnowledgeDraftMapper,
    private val audits: JiraKnowledgeAuditMapper
) {
    fun facts(): List<JiraKnowledgeCandidateFact> {
        val result = mutableListOf<JiraKnowledgeCandidateFact>(); var after = 0L
        while (true) {
            val sourceIds = sources.findJiraIdsAfter(after, BATCH)
            if (sourceIds.isEmpty()) break
            val contentById = contents.findSafe(sourceIds).associateBy { it.sourceId }
            val jiraById = if (contentById.isEmpty()) emptyMap() else
                jira.findBySourceIds(contentById.keys.toList()).associateBy { it.sourceId }
            val eligibleIds = jiraById.values.filter(::baseEligible).map { it.sourceId }
            val drafted = if (eligibleIds.isEmpty()) emptySet() else drafts.findImportedSourceIds(eligibleIds).toSet()
            jiraById.values.filter(::baseEligible).forEach { j ->
                val c = contentById.getValue(j.sourceId)
                result += JiraKnowledgeCandidateFact(j.sourceId,j.jiraKey,c.title,c.description,c.functionModule,c.problemType,
                    j.resolution,j.fixVersion,j.commentSummary,j.resolvedAt,j.sourceId in drafted)
            }
            after = sourceIds.last()
            if (sourceIds.size < BATCH) break
        }
        return result
    }

    fun preview(facts: List<JiraKnowledgeCandidateFact>): JiraKnowledgePreview {
        val eligible = facts.filter(::eligible)
        return JiraKnowledgePreview(eligible.size.toLong(), facts.count { missingEvidence(it) }.toLong(),
            facts.count { excluded(it) }.toLong(), eligible.count { it.alreadyDrafted }.toLong())
    }
    fun candidates(facts: List<JiraKnowledgeCandidateFact>, limit: Int) = facts.filter { eligible(it) && !it.alreadyDrafted }
        .sortedWith(compareByDescending<JiraKnowledgeCandidateFact> { it.resolvedAt }.thenByDescending { it.sourceId }).take(limit)
    fun insert(row: JiraKnowledgeInsertPo): Boolean = drafts.insert(row) == 1
    fun audit(actor: Long, operation: String, detailJson: String) {
        if (audits.insert(actor, operation, detailJson) != 1) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "JIRA_KNOWLEDGE_AUDIT_WRITE_FAILED")
        }
    }

    private fun baseEligible(row: JiraKnowledgeJiraPo): Boolean = row.resolvedAt != null &&
        row.jiraStatusRaw.orEmpty().lowercase() in setOf("resolved","closed","已解决","关闭")
    private fun eligible(row: JiraKnowledgeCandidateFact) = !missingEvidence(row) && !excluded(row)
    private fun missingEvidence(row: JiraKnowledgeCandidateFact) = row.commentSummary?.trim()?.length?.let { it < 10 } ?: true
    private fun excluded(row: JiraKnowledgeCandidateFact) = EXCLUDED.containsMatchIn(row.resolution.orEmpty().lowercase())
    companion object {
        private const val BATCH=500
        private val EXCLUDED=Regex("duplicate|won.t fix|cannot reproduce|重复|不修复|无法复现")
    }
}
