package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import com.feedback.repository.alert.AlertQueryRepository
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.RoleCode
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

@Service
class AlertQueryService(
    private val repository:AlertQueryRepository,
    private val mapper:ObjectMapper,
    private val permissions:DataPermissionService,
    private val lifecycle:AlertIncidentLifecycleService,
    private val escalation:NotificationEscalationService,
){
    fun list(level:String?,status:String?,page:Int,size:Int,alertId:Long?=null,clusterId:Long?=null):Map<String,Any>{
        ensureAlert(page>=1&&size in 1..100){"ALERT_PAGE_INVALID"}
        val normalizedLevel=level?.takeIf(String::isNotBlank)?.also{ensureAlert(it in setOf("P0","P1","P2","P3")){"ALERT_LEVEL_INVALID"}}
        var active=false
        val normalizedStatus=status?.takeIf(String::isNotBlank)?.let{value->if(value.lowercase()=="active"){active=true;null}else value.also{ensureAlert(it in statuses){"ALERT_STATUS_INVALID"}}}
        val query=AlertQueryCriteria(normalizedLevel,normalizedStatus,active,alertId,clusterId)
        val wantedFrom=(page-1)*size;val selected=mutableListOf<AlertQueryInstancePo>();var total=0L;var offset=0
        while(true){
            val batch=repository.page(query,offset,500);if(batch.isEmpty())break
            visibleWithRules(batch).forEach{row->if(total>=wantedFrom&&selected.size<size)selected+=row;total++}
            offset+=batch.size;if(batch.size<500)break
        }
        val rules=repository.ruleMap(selected);val incidents=repository.latestIncidents(selected.map{it.id});val occurrences=repository.latestOccurrences(selected.map{it.id})
        val records=selected.map{row->val rule=rules.getValue(row.ruleId);val affected=occurrences[row.id]?.snapshot?.let(::json)?.path("value")?.asText("0")?:"0";linkedMapOf<String,Any?>(
            "id" to row.id,"level" to row.level,"title" to "[${row.level}] ${rule.ruleName}","ruleName" to rule.ruleName,"ownerTeam" to rule.ownerTeam,
            "status" to row.status,"lifecycleVersion" to row.lifecycleVersion,"triggeredAt" to row.triggeredAt,"incidentId" to incidents[row.id],"clusterId" to row.clusterId,"affectedDevices" to affected)}
        return mapOf("records" to records,"total" to total,"page" to page,"size" to size,"generatedAt" to LocalDateTime.now(),"metricVersion" to "alert-center-v2")
    }

    fun statistics():Map<String,Long>{
        val counts=mutableMapOf("P0" to 0L,"P1" to 0L,"P2" to 0L,"P3" to 0L);var processed=0L;val today=LocalDate.now()
        val todayStart=today.atStartOfDay()
        scanAll{rows->visibleWithRules(rows).forEach{row->if(row.status in setOf("FIRING","ACKNOWLEDGED"))counts[row.level]=(counts[row.level]?:0)+1;if(row.resolvedAt?.let{!it.isBefore(todayStart)}==true)processed++}}
        return counts+mapOf("processedToday" to processed)
    }

    fun p3Digest():Map<String,Any>{
        val date=LocalDate.now().minusDays(1);var count=0L
        scanAll{rows->visibleWithRules(rows).forEach{if(it.level=="P3"&&it.triggeredAt.toLocalDate()==date)count++}}
        return mapOf("date" to date,"count" to count,"generatedAt" to LocalDateTime.now(),"schedule" to "09:00")
    }

    fun assignees()=repository.assignees().map{mapOf("id" to it.id,"displayName" to it.displayName)}

    fun detail(id:Long):Map<String,Any?>{
        val (instance,rule)=visibleInstance(id)
        val incidentId=repository.latestIncidents(listOf(id))[id];val incident=incidentId?.let(repository::incident);val occurrence=repository.latestOccurrences(listOf(id))[id]
        val result=linkedMapOf<String,Any?>(
            "id" to instance.id,"level" to instance.level,"title" to "[${instance.level}] ${rule.ruleName}","ruleCode" to rule.ruleCode,
            "ruleName" to rule.ruleName,"ownerTeam" to rule.ownerTeam,"conditionJson" to rule.conditionJson,"routeJson" to rule.routeJson,
            "status" to instance.status,"lifecycleVersion" to instance.lifecycleVersion,"statusReason" to instance.statusReason,"triggeredAt" to instance.triggeredAt,
            "incidentId" to incidentId,"clusterId" to instance.clusterId,"incidentStatus" to incident?.status,"incidentLifecycleVersion" to incident?.lifecycleVersion,
            "commanderId" to incident?.commanderId,"commanderName" to repository.userName(incident?.commanderId),"snapshot" to occurrence?.snapshot,
        )
        val snap=result.remove("snapshot")?.toString()?.let(::json)
        result["affectedDevices"]=snap?.path("affectedDevices")?.asLong(snap.path("value").asLong(0))?:0
        result["impactScore"]=snap?.path("impactScore")?.asDouble(snap.path("value").asDouble(0.0))?:0.0
        result["sameSourceProbability"]=snap?.path("sameSourceProbability")?.asDouble(0.0)?:0.0
        result["condition"]=json(result.remove("conditionJson"));result["route"]=json(result.remove("routeJson"))
        result["escalations"]=timeline(id,instance.triggeredAt);result["escalationChain"]=escalationChain(id)
        result["quickRca"]=incidentId?.let{rca(it,"QUICK")};result["deepRca"]=incidentId?.let{rca(it,"DEEP")}
        result["knowledgeMatches"]=instance.clusterId?.let(::knowledge)?:emptyList<Any>();result["activeSilence"]=activeSilence(id)
        return result
    }

    @Transactional fun acknowledge(id:Long,expectedVersion:Long,actor:Long){ensureVisible(id);lifecycle.transitionAlert(id,"ACKNOWLEDGED",expectedVersion,"人工确认告警",actor);val chainAcknowledged=escalation.acknowledgeAlert(id,actor);audit(actor,"ALERT_ACK",id,mapOf("fromVersion" to expectedVersion,"toVersion" to expectedVersion+1,"escalationChainAcknowledged" to chainAcknowledged))}
    @Transactional fun resolve(id:Long,resolution:String,expectedVersion:Long,actor:Long){ensureAlert(resolution.trim().length in 2..500){"ALERT_RESOLUTION_REQUIRED"};ensureVisible(id);ensureAlert(repository.status(id)=="ACKNOWLEDGED"){"ALERT_ACK_REQUIRED"};lifecycle.transitionAlert(id,"RESOLVED",expectedVersion,resolution,actor,mapOf("conclusion" to resolution.trim()));audit(actor,"ALERT_RESOLVE",id,mapOf("resolution" to resolution.trim(),"fromVersion" to expectedVersion,"toVersion" to expectedVersion+1))}

    @Transactional fun transfer(id:Long,assigneeId:Long,reason:String,expectedIncidentVersion:Long,actor:Long){
        ensureAlert(reason.trim().length in 2..300){"TRANSFER_REASON_REQUIRED"};ensureVisible(id);ensureAlert(repository.eligibleUser(assigneeId)){"ASSIGNEE_INVALID"}
        val changed=repository.activeIncidentLinks(id).count{repository.transferIncident(it.incidentId,assigneeId,reason.trim(),actor,expectedIncidentVersion)}
        ensureAlert(changed==1){"INCIDENT_VERSION_CONFLICT"};audit(actor,"ALERT_TRANSFER",id,mapOf("assigneeId" to assigneeId,"reason" to reason.trim(),"fromIncidentVersion" to expectedIncidentVersion,"toIncidentVersion" to expectedIncidentVersion+1))
    }

    @Transactional fun silence(id:Long,minutes:Int,reason:String,expectedVersion:Long,actor:Long){
        ensureAlert(minutes in setOf(30,60,120,240)){"SILENCE_DURATION_INVALID"};ensureAlert(reason.trim().length in 2..300){"SILENCE_REASON_REQUIRED"};ensureVisible(id)
        val row=alertStateRequired(repository.lockInstance(id));ensureAlert(row.lifecycleVersion==expectedVersion){"ALERT_VERSION_CONFLICT"};ensureAlert(row.status in setOf("FIRING","ACKNOWLEDGED","ESCALATED")){"ALERT_SILENCE_STATUS_INVALID"};ensureAlert(row.level!="P0"){"P0_SILENCE_FORBIDDEN"};ensureAlert(!repository.hasActiveSilence(id)){"ALERT_ALREADY_SILENCED"}
        repository.insertSilence(id,reason.trim(),minutes,actor);audit(actor,"ALERT_SILENCE",id,mapOf("minutes" to minutes,"reason" to reason.trim(),"alertVersion" to expectedVersion))
    }

    fun incidents(page:Int,size:Int):Map<String,Any>{ensureAlert(page>=1&&size in 1..100);val records=repository.incidentPage(size,(page-1)*size).map{mapOf("id" to it.id,"title" to it.title,"status" to it.status,"severity" to it.severity,"openedAt" to it.openedAt,"resolvedAt" to it.resolvedAt)};return mapOf("records" to records,"total" to repository.incidentCount(),"page" to page,"size" to size)}

    fun evidence(id:Long):Map<String,Any?>{val(instance,rule)=visibleInstance(id);val occurrence=repository.latestOccurrences(listOf(instance.id))[instance.id];return linkedMapOf("alertSource" to rule.category,"ruleCode" to rule.ruleCode,"ruleName" to rule.ruleName,"evidence" to occurrence?.snapshot?.let(::json),"evaluatedAt" to occurrence?.evaluatedAt,"containsRawContent" to false)}

    fun incident(id:Long):Map<String,Any?> = repository.incidentFull(id)?.let{row->linkedMapOf(
        "id" to row.id,"public_id" to row.publicId,"incident_title" to row.incidentTitle,"incident_status" to row.incidentStatus,"lifecycle_version" to row.lifecycleVersion,
        "incident_commander" to row.incidentCommander,"investigation_started_at" to row.investigationStartedAt,"incident_severity" to row.incidentSeverity,
        "opened_at" to row.openedAt,"resolved_at" to row.resolvedAt,"resolution_summary" to row.resolutionSummary,"closed_at" to row.closedAt,
        "closure_summary" to row.closureSummary,"status_changed_by" to row.statusChangedBy,"status_reason" to row.statusReason,"archived_at" to row.archivedAt,
        "created_at" to row.createdAt,"updated_at" to row.updatedAt)}?:alertMissing("INCIDENT_NOT_FOUND")

    private fun visibleWithRules(rows:List<AlertQueryInstancePo>):List<AlertQueryInstancePo>{
        val withRule=rows.filter{it.ruleId in repository.ruleMap(rows)};if(isAdmin())return withRule
        val scope=permissions.scope();val facts=repository.visibility(withRule);val operator=isOperator()
        return withRule.filter{row->(operator&&row.feedbackSourceId==null&&row.clusterId==null)||facts[row.id].orEmpty().any{scope.permits(it.channel,it.module,it.province,it.city)}}
    }
    private fun visibleInstance(id:Long):Pair<AlertQueryInstancePo,AlertQueryRulePo>{val row=repository.instance(id)?:alertMissing("ALERT_NOT_FOUND");val rule=repository.ruleMap(listOf(row))[row.ruleId]?:alertMissing("ALERT_NOT_FOUND");if(!isAdmin()){val scope=permissions.scope();val visible=(isOperator()&&row.feedbackSourceId==null&&row.clusterId==null)||repository.visibility(listOf(row))[row.id].orEmpty().any{scope.permits(it.channel,it.module,it.province,it.city)};if(!visible)alertMissing("ALERT_NOT_FOUND")};return row to rule}
    private fun ensureVisible(id:Long){visibleInstance(id)}
    private fun scanAll(consumer:(List<AlertQueryInstancePo>)->Unit){var after=0L;while(true){val page=repository.pageAll(after,500);if(page.isEmpty())break;consumer(page);after=page.last().id;if(page.size<500)break}}
    private fun isAdmin()=(principal()?.roles?.contains(RoleCode.ADMIN)==true)
    private fun isOperator()=(principal()?.roles?.contains(RoleCode.OPERATOR)==true)
    private fun principal()=SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal
    private fun json(value:Any?)=value?.toString()?.let(mapper::readTree)

    private fun rca(id:Long,type:String)=repository.rca(id,type)?.let{row->linkedMapOf<String,Any?>("id" to row.id,"title" to row.title,"description" to row.description,"solution" to row.solution,"confidence" to row.confidence,"status" to row.status,"orchestrationVersion" to row.orchestrationVersion,"correlationJson" to row.correlationJson,"impactJson" to row.impactJson,"validationStatus" to row.validationStatus,"validationJson" to row.validationJson,"factsSha256" to row.factsSha256,"createdAt" to row.createdAt).apply{this["correlation"]=json(remove("correlationJson"));this["impact"]=json(remove("impactJson"));this["validation"]=json(remove("validationJson"));this["needsHumanReview"]=(row.confidence?.toDouble()?:0.0)<.7}}
    private fun knowledge(clusterId:Long)=repository.knowledge(clusterId).map{mapOf("id" to it.id,"title" to it.title,"solution" to it.solution,"featureModule" to it.featureModule,"issueType" to it.issueType,"shownCount" to it.shownCount,"adoptionCount" to it.adoptionCount,"effectivenessScore" to it.effectivenessScore)}
    private fun activeSilence(id:Long)=repository.activeSilence(id)?.let{mapOf("id" to it.id,"endsAt" to it.endsAt,"scope" to it.scope)}
    private fun escalationChain(id:Long):Map<String,Any?>?{val chain=repository.chain(id)?:return null;val incidentSeverity=chain.incidentId?.let(repository::incident)?.severity;val targets=repository.chainTargets(chain.id).map{mapOf("targetOrder" to it.targetOrder,"targetType" to it.targetType,"displayName" to it.displayName,"status" to it.status,"channels" to it.channels)};val noOncall=chain.exhaustedReasonCode=="NO_ELIGIBLE_ONCALL";return linkedMapOf("id" to chain.id,"status" to chain.status,"currentTarget" to chain.currentTarget,"currentChannel" to chain.currentChannel,"confirmationDeadline" to chain.confirmationDeadline,"lastFailureReason" to chain.lastFailureReason,"exhaustedReasonCode" to chain.exhaustedReasonCode,"notificationRisk" to chain.notificationRisk,"ownerTeam" to chain.ownerTeam,"originalIncidentSeverity" to chain.originalIncidentSeverity,"acknowledgedAt" to chain.acknowledgedAt,"incidentSeverity" to incidentSeverity,"targets" to targets,"targetCount" to targets.size,"noEligibleOncall" to noOncall,"incidentSeverityEscalated" to (incidentSeverity!=null&&incidentSeverity!=chain.originalIncidentSeverity),"recommendedAction" to if(noOncall)"配置当前有效值班人或团队负责人；紧急情况下先人工转派" else "立即人工接管并核对通知渠道失败记录","configurationPath" to "/admin/alerts?section=oncall")}
    private fun timeline(id:Long,triggered:Any?):List<Map<String,Any?>>{val notifications=repository.notificationTimeline(id);val names=repository.targetNames(notifications.mapNotNull{it.escalationTargetId});val events=mutableListOf<Map<String,Any?>>(mapOf("id" to "fired","time" to triggered,"action" to "告警触发","type" to "danger"));events+=notifications.map{row->mapOf("id" to "n-${row.id}","time" to row.createdAt,"action" to "${row.escalationTargetId?.let(names::get)?:"系统"} · ${row.channel} · ${row.status}${if(row.acknowledgedAt!=null)" · 已确认" else ""}","type" to if(row.acknowledgedAt!=null)"success" else if(row.status in setOf("FAILED","DEAD_LETTER"))"danger" else "primary")};events+=repository.auditTimeline(id).map{mapOf("id" to "a-${it.id}","time" to it.time,"action" to it.operation,"type" to if(it.operation=="ALERT_RESOLVE")"success" else "primary")};return events.sortedBy{it["time"].toString()}}
    private fun audit(actor:Long,operation:String,id:Long,detail:Map<String,Any?>)=repository.audit(actor,operation,id,mapper.writeValueAsString(detail))
    private val statuses=setOf("PENDING","FIRING","ACKNOWLEDGED","SUPPRESSED","ESCALATED","RECOVERED","RESOLVED","CLOSED","ARCHIVED")
}
