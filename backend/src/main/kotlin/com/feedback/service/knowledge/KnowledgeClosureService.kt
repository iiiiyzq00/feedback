package com.feedback.service.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.po.knowledge.KnowledgeUsageAuditInsertPo
import com.feedback.repository.knowledge.KnowledgeClosureRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class KnowledgeUsageInput(val eventType:String,val reason:String?=null,val alertInstanceId:Long?=null)

@Service
class KnowledgeClosureService(private val repository:KnowledgeClosureRepository,private val mapper:ObjectMapper){
    @Transactional fun usage(incidentId:Long,knowledgeId:Long,input:KnowledgeUsageInput,actor:Long):Map<String,Any?>{
        val event=input.eventType.uppercase()
        if(event !in setOf("SHOWN","ADOPTED","EFFECTIVE","INEFFECTIVE")) invalid("KNOWLEDGE_USAGE_EVENT_INVALID")
        if(event in setOf("EFFECTIVE","INEFFECTIVE") && (input.reason.isNullOrBlank() || input.reason.length>500))
            invalid("KNOWLEDGE_FEEDBACK_REASON_REQUIRED")
        if(!repository.isPublished(knowledgeId)) invalid("KNOWLEDGE_NOT_PUBLISHED")
        if(!repository.incidentExists(incidentId)) throw ClientException(ErrorCode.NOT_FOUND,"INCIDENT_NOT_FOUND")
        if(event in setOf("EFFECTIVE","INEFFECTIVE") && !repository.adoptionExists(knowledgeId,incidentId))
            invalid("KNOWLEDGE_ADOPTION_REQUIRED")
        val detail=mapper.writeValueAsString(mapOf("knowledgeId" to knowledgeId,"incidentId" to incidentId,
            "alertInstanceId" to input.alertInstanceId,"eventType" to event,"reason" to input.reason?.trim()))
        val inserted=repository.insertUsage(KnowledgeUsageAuditInsertPo(actor,"KNOWLEDGE_USAGE_$event",
            knowledgeId.toString(),repository.correlationId(incidentId),detail,"$knowledgeId:$incidentId:$event"))
        repository.recalculate(knowledgeId)
        return statistics(knowledgeId)+(if(inserted) mapOf("recorded" to true) else mapOf("recorded" to false,"idempotent" to true))
    }
    fun statistics(id:Long):Map<String,Any?> = repository.statistics(id).let { row -> mapOf("id" to row.id,
        "shownCount" to row.shownCount,"adoptionCount" to row.adoptionCount,"effectiveCount" to row.effectiveCount,
        "ineffectiveCount" to row.ineffectiveCount,"effectivenessScore" to row.effectivenessScore,"lastUsedAt" to row.lastUsedAt) }

    private fun invalid(reasonCode:String):Nothing = throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
}
