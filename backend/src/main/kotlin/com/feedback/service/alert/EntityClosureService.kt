package com.feedback.service.alert

import com.feedback.repository.alert.EntityClosureRepository
import com.feedback.service.auth.DataPermissionService
import org.springframework.stereotype.Service

@Service
class EntityClosureService(private val repository:EntityClosureRepository,private val permissions:DataPermissionService){
    fun snapshot(incidentId:Long):Map<String,Any>{
        val facts=repository.facts(incidentId);val scope=permissions.scope()
        if(facts.sources.any{!scope.permits(it.sourceChannel,it.functionModule,it.provinceCode,it.cityCode)})alertDeny("DATA_SCOPE_ACCESS_DENIED")
        val counts=facts.counts
        val checks=listOf(
            ensureAlertState("LINKED_CLUSTER_PRESENT",counts.clusters>0),
            ensureAlertState("ALL_CLUSTERS_RESOLVED",counts.clusters>0&&counts.clusters==counts.resolvedClusters),
            ensureAlertState("LINKED_RCA_PRESENT",counts.rcas>0),
            ensureAlertState("ALL_RCAS_REVIEWED",counts.rcas>0&&counts.rcas==counts.reviewedRcas),
            ensureAlertState("ALL_RCAS_HAVE_PUBLISHED_KNOWLEDGE",counts.rcas>0&&counts.rcas==counts.rcasWithPublishedKnowledge),
            ensureAlertState("EFFECTIVE_KNOWLEDGE_RECORDED",counts.effectiveKnowledge>0),
        )
        val feedbackClosed=facts.sources.count{it.processingStatus=="CLOSED"}.toLong();val ready=checks.all{it["passed"]==true}
        val closed=facts.incident.status=="CLOSED"&&ready&&facts.sources.isNotEmpty()&&feedbackClosed==facts.sources.size.toLong()
        return linkedMapOf(
            "status" to if(closed)"PASSED" else if(ready)"READY" else "BLOCKED",
            "incident" to mapOf("status" to facts.incident.status,"version" to facts.incident.version),
            "counts" to mapOf("clusters" to counts.clusters,"resolvedClusters" to counts.resolvedClusters,"rcas" to counts.rcas,
                "reviewedRcas" to counts.reviewedRcas,"rcasWithPublishedKnowledge" to counts.rcasWithPublishedKnowledge,
                "publishedKnowledge" to counts.publishedKnowledge,"effectiveKnowledge" to counts.effectiveKnowledge,
                "feedback" to facts.sources.size.toLong(),"closedFeedback" to feedbackClosed),
            "checks" to checks,"metricVersion" to "entity-closure-v1",
        )
    }

    fun closeLinkedFeedback(incidentId:Long,actor:Long,reason:String){
        if(repository.linkedCount(incidentId)==0)return
        val state=snapshot(incidentId)
        ensureAlert(state["status"]=="READY"){"ENTITY_CLOSURE_PREREQUISITES_NOT_MET"}
        repository.closeSources(incidentId,actor)
        repository.auditClose(incidentId,actor,reason)
    }

    private fun ensureAlertState(code:String,passed:Boolean)=mapOf("check" to code,"passed" to passed)
}
