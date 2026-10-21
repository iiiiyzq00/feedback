package com.feedback.service.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.po.knowledge.JiraKnowledgeInsertPo
import com.feedback.repository.knowledge.JiraKnowledgeCandidateFact
import com.feedback.repository.knowledge.JiraKnowledgeCandidateRepository
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.PlatformPermissions
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

data class JiraKnowledgeCandidateRequest(val limit:Int=100)

@Service
class JiraKnowledgeCandidateService(
    private val repository:JiraKnowledgeCandidateRepository,
    private val mapper:ObjectMapper
) {
    fun preview():Map<String,Any> {
        val preview=repository.preview(repository.facts())
        return mapOf("eligible" to preview.eligible,"missingResolutionEvidence" to preview.missingEvidence,
            "excludedResolution" to preview.excludedResolution,"alreadyDrafted" to preview.alreadyDrafted,
            "definition" to "仅隐私PASS、已解决、具备评论处置证据且非重复/不修复/无法复现的Jira反馈")
    }

    @Transactional
    fun generate(input:JiraKnowledgeCandidateRequest):Map<String,Any> {
        val principal=principal()
        if(PlatformPermissions.GENERATE_JIRA_KNOWLEDGE_CANDIDATES !in principal.permissions)
            throw ClientException(ErrorCode.AUTH_FORBIDDEN,"JIRA_KNOWLEDGE_CANDIDATE_PERMISSION_REQUIRED")
        if(input.limit !in 1..500) throw ClientException(ErrorCode.BAD_REQUEST,"JIRA_KNOWLEDGE_CANDIDATE_LIMIT_INVALID")
        val candidates=repository.candidates(repository.facts(),input.limit)
        var created=0;var duplicates=0
        candidates.forEach { row ->
            val sourceId=row.sourceId
            val title=row.title?.trim().orEmpty().take(200).ifBlank{"Jira ${row.jiraKey} 解决经验候选"}
            val module=normalizeModule(row.module)
            val issueType=row.issueType?.take(50)?.ifBlank{"OTHER"}?:"OTHER"
            val solution=row.commentSummary.toString().trim().take(5000)
            val rootCause="待人工根据Jira描述与处置评论确认根因："+row.description.toString().trim().take(4500)
            val fingerprint=sha256("$module\u001f$issueType\u001f${title.lowercase()}\u001f${solution.lowercase()}".toByteArray())
            val score=score(row)
            val evidence=mapper.writeValueAsString(mapOf("sourceFeedbackId" to sourceId,"jiraKey" to row.jiraKey,
                "resolution" to row.resolution,"fixVersion" to row.fixVersion,"resolvedAt" to row.resolvedAt,
                "privacyStatus" to "PASS","requiresHumanRootCauseReview" to true))
            val content=mapper.writeValueAsString(mapOf("rootCause" to rootCause,"solution" to solution))
            val inserted=repository.insert(JiraKnowledgeInsertPo(title,content,
                mapper.writeValueAsString(listOf(module,issueType,"JIRA_SEED_CANDIDATE")),module,issueType,sourceId,
                fingerprint,score,evidence,principal.userId))
            if(inserted)created++ else duplicates++
        }
        repository.audit(principal.userId,"JIRA_KNOWLEDGE_CANDIDATES_GENERATED",mapper.writeValueAsString(
            mapOf("requestedLimit" to input.limit,"selected" to candidates.size,"created" to created,"duplicates" to duplicates)))
        return preview()+mapOf("selected" to candidates.size,"created" to created,"duplicates" to duplicates)
    }

    private fun score(row:JiraKnowledgeCandidateFact):Double = (0.55+
        (if(!row.fixVersion.isNullOrBlank())0.15 else 0.0)+
        (if(row.commentSummary.toString().length>=100)0.15 else 0.05)+
        (if(!row.resolution.isNullOrBlank())0.10 else 0.0)).coerceAtMost(0.95)
    private fun normalizeModule(value:String?)=when(value?.uppercase()){
        "AI_TUTOR","SYNC_COURSE","WRONG_BOOK","CAMERA_MIC","VIDEO_PLAYBACK"->value.uppercase();else->"OTHER"}
    private fun principal()=SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal
        ?:throw ClientException(ErrorCode.AUTH_UNAUTHORIZED,"AUTHENTICATION_REQUIRED")
    private fun sha256(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value)
}
