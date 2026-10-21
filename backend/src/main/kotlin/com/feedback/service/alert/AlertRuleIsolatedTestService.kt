package com.feedback.service.alert

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.security.WebPrincipal
import com.feedback.model.po.alert.IsolatedDrillInsertPo
import com.feedback.model.po.alert.IsolatedTestInsertPo
import com.feedback.repository.alert.AlertMetricRepository
import com.feedback.repository.alert.AlertRuleIsolatedRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class ReplayRuleInput(val ruleId:Long,val start:LocalDateTime,val end:LocalDateTime)
data class ShadowRuleInput(val ruleId:Long)
data class DrillRuleInput(val ruleId:Long,val metricValues:Map<String,Double>,val title:String?=null)
data class DrillDispositionInput(val reason:String)

@Service
class AlertRuleIsolatedTestService(private val repository:AlertRuleIsolatedRepository,private val metrics:AlertMetricRepository,private val mapper:ObjectMapper,private val trendBaselines:AlertTrendBaselineService,private val versionReleases:VersionReleaseFactService,private val reviewGovernance:AlertReviewGovernanceMetricService,private val qualityFacts:AlertQualityFactService,private val securitySignals:SecuritySignalService,private val alertRouteSkill:AlertRouteSkillService,private val knowledgeSearch:com.feedback.service.ai.KnowledgeSearchSkillService){
    @Transactional fun replay(input:ReplayRuleInput):Map<String,Any?>{
        ensureAlert(input.start.isBefore(input.end)&&!input.end.isAfter(LocalDateTime.now())&&!input.end.isAfter(input.start.plusDays(31))){"回放时间必须是最长31天的历史区间"}
        val rule=rule(input.ruleId);val expression=mapper.readTree(rule["trigger_expression"].toString());val conditions=conditions(expression)
        val evaluations=conditions.map{c->val metric=c.path("metric").asText();val value=historicalMetric(metric,input.start,input.end);evaluate(c,value)}
        val matched=combined(expression,evaluations);val sampleSize=historicalSampleSize(input.start,input.end)
        return persist(rule,"REPLAY",matched,mapOf("start" to input.start,"end" to input.end),evaluations,sampleSize,input.start,input.end)
    }

    @Transactional fun shadow(input:ShadowRuleInput):Map<String,Any?>{
        val rule=rule(input.ruleId);val expression=mapper.readTree(rule["trigger_expression"].toString());val evaluations=conditions(expression).map{c->evaluate(c,currentMetric(c.path("metric").asText(),windowMinutes(c.path("window").asText())))}
        return persist(rule,"SHADOW",combined(expression,evaluations),mapOf("evaluatedAt" to LocalDateTime.now()),evaluations,evaluations.size.toLong(),null,null)
    }

    @Transactional fun drill(input:DrillRuleInput):Map<String,Any?>{
        ensureAlert(input.title==null||input.title.length in 1..200);val rule=rule(input.ruleId);val expression=mapper.readTree(rule["trigger_expression"].toString());val evaluations=conditions(expression).map{c->evaluate(c,input.metricValues[c.path("metric").asText()]?:0.0)};val matched=combined(expression,evaluations)
        val result=persist(rule,"DRILL",matched,input.metricValues,evaluations,input.metricValues.size.toLong(),null,null);val actor=actor();val title=input.title?.trim()?.takeIf(String::isNotBlank)?:"${rule["rule_name"]}演练"
        val runId=(result["runId"] as Number).toLong();val routing=mapper.readTree(rule["routing_config"].toString());val conditionList=conditions(expression);val primaryIndex=drillPrimaryConditionIndex(evaluations)
        if(matched){
            val primary=conditionList[primaryIndex];val currentValue=(evaluations[primaryIndex]["value"] as? Number)?.toDouble()?:0.0
            val fallbackSummary="${primary.path("metric").asText()} 演练值 $currentValue，触发阈值 ${primary.path("threshold").asDouble()}"
            val advice=alertRouteSkill.adviseForDrill(runId,mapOf("drill" to true,"testRunId" to runId,"ruleCode" to rule["rule_code"],"ruleName" to rule["rule_name"],"severity" to rule["default_severity"],"metric" to primary.path("metric").asText(),"currentValue" to currentValue,"threshold" to primary.path("threshold").asDouble(),"window" to primary.path("window").asText(),"ownerTeam" to rule["owner_team"],"fallbackSummary" to fallbackSummary))
            val knowledge=knowledgeSearch.search("alert-drill-$runId",expression.path("issueType").asText(null),routing.path("module").asText(null))
            result["routeAdvice"]=mapOf("summary" to advice.summary,"suggestedActions" to advice.suggestedActions,"knowledgeMatches" to knowledge,"degraded" to advice.degraded)
        }
        result["routingSkillExecuted"]=matched
        repository.updateResult(runId,mapper.writeValueAsString(result));result["drillId"]=repository.insertDrill(IsolatedDrillInsertPo(ruleId=input.ruleId,parentId=runId,ruleCode=rule["rule_code"].toString(),title=title,severity=rule["default_severity"].toString(),actor=actor));result["drillStatus"]="OPEN";result["productionImpact"]=false;return result
    }

    fun drills(limit:Int):List<Map<String,Any?>>{ensureAlert(limit in 1..200);return repository.drills(limit).map{linkedMapOf("id" to it.id,"testRunId" to it.testRunId,"ruleId" to it.ruleId,"ruleCode" to it.ruleCode,"title" to it.title,"severity" to it.severity,"status" to it.status,"notificationChannel" to it.notificationChannel,"productionImpact" to it.productionImpact,"createdAt" to it.createdAt,"createdByName" to it.createdByName)}}

    @Transactional fun acknowledgeDrill(id:Long,input:DrillDispositionInput)=transitionDrill(id,"ACKNOWLEDGED",input.reason)
    @Transactional fun resolveDrill(id:Long,input:DrillDispositionInput)=transitionDrill(id,"RESOLVED",input.reason)

    private fun transitionDrill(id:Long,target:String,reason:String):Map<String,Any?>{
        ensureAlert(reason.trim().length in 2..300){"演练处置说明长度应为2至300字"}
        val row=repository.lockDrill(id);ensureAlert(!row.productionImpact&&row.notificationChannel=="IN_APP"){"ALERT_DRILL_ISOLATION_REQUIRED"}
        val current=row.status;val valid=when(target){"ACKNOWLEDGED"->current=="OPEN";"RESOLVED"->current=="ACKNOWLEDGED";else->false}
        ensureAlert(valid){"ALERT_DRILL_STATUS_INVALID:$current->$target"}
        val actor=actor();repository.transition(row,target,reason.trim(),actor,id)
        return drills(200).first{(it["id"]as Number).toLong()==id}
    }

    private fun persist(rule:Map<String,Any?>,mode:String,matched:Boolean,input:Any,evaluations:List<Map<String,Any>>,sampleSize:Long,start:LocalDateTime?,end:LocalDateTime?):LinkedHashMap<String,Any?>{
        val result=linkedMapOf<String,Any?>("mode" to mode,"matched" to matched,"conditions" to evaluations,"sampleSize" to sampleSize,"ownerTeam" to rule["owner_team"],"externalNotificationsSent" to 0,"notificationPolicy" to "IN_APP_TEST_ONLY")
        result["runId"]=repository.insertTest(IsolatedTestInsertPo(ruleId=(rule["id"] as Number).toLong(),mode=mode,matched=matched,inputJson=mapper.writeValueAsString(input),resultJson=mapper.writeValueAsString(result),start=start,end=end,sampleSize=sampleSize,actor=actor()));return result
    }
    private fun rule(id:Long):Map<String,Any?>{val r=repository.rule(id);return mapOf("id" to r.id,"rule_code" to r.ruleCode,"rule_name" to r.ruleName,"default_severity" to r.defaultSeverity,"trigger_expression" to r.triggerExpression,"recovery_expression" to r.recoveryExpression,"routing_config" to r.routingConfig,"cooldown_minutes" to r.cooldownMinutes,"owner_team" to r.ownerTeam)}
    private fun conditions(e:JsonNode)=e.path("conditions").takeIf(JsonNode::isArray)?.toList().orEmpty().ifEmpty{listOf(e)}
    private fun evaluate(c:JsonNode,value:Double):Map<String,Any>{val op=c.path("operator").asText(">=");val threshold=c.path("threshold").asDouble();return mapOf("metric" to c.path("metric").asText(),"window" to c.path("window").asText(),"operator" to op,"threshold" to threshold,"value" to value,"matched" to compare(value,op,threshold))}
    private fun combined(e:JsonNode,rs:List<Map<String,Any>>)=if(e.path("combinator").asText("ALL")=="ANY")rs.any{it["matched"]==true}else rs.all{it["matched"]==true}
    private fun historicalSampleSize(start:LocalDateTime,end:LocalDateTime)=metrics.sampleSize(start,end)
    private fun historicalMetric(metric:String,start:LocalDateTime,end:LocalDateTime):Double=when(metric){
        "FEEDBACK_SPIKE"->metrics.feedbackSpike(start,end);"ERROR_RATE_HIGH"->metrics.errorRate(start,end);"CRITICAL_RATIO"->metrics.criticalRatio(start,end)
        "SDK_REQUEST_COUNT"->metrics.sdkCount(start,end);"SDK_NON_RATE_LIMIT_FAILURE_RATE"->metrics.sdkNonRateLimitFailureRate(start,end);"SDK_RATE_LIMIT_RATE"->metrics.sdkRateLimitRate(start,end);"CHANNEL_MAX_STALE_MINUTES"->metrics.channelMaxStale(end)
        "IMPORT_FAILED_BATCH_COUNT"->metrics.importFailed(start,end);"IMPORT_ROW_COUNT"->metrics.importRows(start,end);"IMPORT_FAILURE_RATE"->metrics.importFailureRate(start,end);"MAPPING_ROW_COUNT"->metrics.mappingRows(start,end);"MAPPING_ERROR_RATE"->metrics.mappingErrorRate(start,end)
        "MASKING_ATTEMPT_COUNT"->metrics.maskingAttempts(start,end);"MASKING_FAILURE_RATE"->metrics.maskingFailureRate(start,end);"MASKING_BACKLOG_COUNT"->metrics.maskingBacklog(start,end);"MASKING_OLDEST_PENDING_MINUTES"->metrics.maskingOldestPending(start,end);"QUALITY_SAMPLE_COUNT"->metrics.qualityRows(start,end);"QUALITY_SCORE_PERCENT"->metrics.qualityScore(start,end);"UNHANDLED_IDEMPOTENCY_CONFLICT_COUNT"->metrics.openIdempotencyConflicts(start,end)
        "CONTACT_ESCALATION_CANDIDATE_COUNT"->metrics.contactEscalationCandidates(start,end);"RESOLVED_REOCCURRENCE_CANDIDATE_COUNT"->metrics.resolvedReoccurrenceCandidates(start,end);"CONTENT_ERROR_SPIKE_CANDIDATE_COUNT"->metrics.contentErrorCandidates(start,end);"REDBLACK_CLUSTER_FLOOD_CANDIDATE_COUNT"->metrics.redblackFloodCandidates(start,end);"LONG_UNRESOLVED_CLUSTER_COUNT"->metrics.longUnresolvedClusters(end);"DEVICE_PERSISTENT_FAILURE_CANDIDATE_COUNT"->metrics.devicePersistentCandidates(end);"URGENT_PROCESSING_SLA_BREACH_COUNT"->metrics.urgentBreaches(start,end);"FAILED_RECOVERY_SLA_BREACH_COUNT"->metrics.failedRecoveryBreaches(start,end)
        "CLUSTER_GROWTH_ANOMALY_COUNT"->trendBaselines.clusterAnomalyCount(end);"NEGATIVE_SENTIMENT_SURGE_INDICATOR"->trendBaselines.globalAnomaly("NEGATIVE_SENTIMENT",metrics.negativeSentimentCount(end),end,6.0,20);"SDK_VOLUME_ANOMALY_INDICATOR"->trendBaselines.globalAnomaly("SDK_VOLUME",metrics.sdkVolume(end),end,3.0,20);"VERSION_REGRESSION_CANDIDATE_COUNT"->versionReleases.regressionCandidates(end)
        "CLUSTER_REVIEW_SLA_BREACH_COUNT"->reviewGovernance.clusterSlaBreaches(end);"MASKING_REVIEW_SLA_BREACH_COUNT"->reviewGovernance.maskingSlaBreaches(end);"CLUSTER_REVIEW_DECISION_COUNT"->reviewGovernance.reviewDecisionCount(end);"CLUSTER_MANUAL_OVERTURN_RATE"->reviewGovernance.manualOverturnRate(end);"RULE_INPUT_COMPLETENESS_PERCENT"->reviewGovernance.ruleInputCompleteness(end)
        "MASKING_LATEST_END_TO_END_F1_PERCENT"->qualityFacts.maskingF1(end);"MASKING_LATEST_HIGH_RISK_RECALL_PERCENT"->qualityFacts.maskingHighRiskRecall(end);"OPEN_INCORRECT_DEDUP_COUNT"->qualityFacts.openIncorrectDedup(end);"ATTACHMENT_FINAL_COUNT"->qualityFacts.attachmentFinalCount(start,end);"ATTACHMENT_FINAL_FAILURE_RATE"->qualityFacts.attachmentFailureRate(start,end)
        "OPEN_DEVICE_SAFETY_SUSPECTED_COUNT"->securitySignals.metric("DEVICE_SAFETY","SUSPECTED",end);"OPEN_DEVICE_SAFETY_STRONG_COUNT"->securitySignals.metric("DEVICE_SAFETY","STRONG",end);"OPEN_SENSITIVE_RESIDUAL_COUNT"->securitySignals.metric("SENSITIVE_RESIDUAL_BLOCKED",null,end);"OPEN_SENSITIVE_EXPOSURE_COUNT"->securitySignals.metric("SENSITIVE_EXPOSURE_CONFIRMED","CONFIRMED",end)
        "USER_SAFETY_IMMINENT_COUNT"->metrics.userRiskCount(start,end,"SAFETY");"USER_PRIVACY_EXPOSURE_COUNT"->metrics.userRiskCount(start,end,"PRIVACY");"DEVICE_BRICKED_AFTER_UPGRADE_COUNT"->metrics.userRiskCount(start,end,"BRICK");"USER_DATA_LOSS_IRRECOVERABLE_COUNT"->metrics.userRiskCount(start,end,"LOSS");"CHILD_CONTENT_SAFETY_COUNT"->metrics.userRiskCount(start,end,"CHILD")
        "CRITICAL_CLUSTER_SURGE_COUNT"->metrics.criticalClusterSurge(start,end);"CRITICAL_RESOLVED_RECURRENCE_COUNT"->metrics.resolvedReoccurrenceCandidates(start,end,true);"DEVICE_MODEL_WIDE_FAILURE_COUNT"->metrics.deviceModelWideFailure(start,end);"PUBLIC_NEGATIVE_FLARE_COUNT"->metrics.publicNegativeFlare(start,end);"VERSION_BLOCKING_REGRESSION_COUNT"->versionReleases.blockingRegressionCandidates(end);else->0.0
    }
    private fun currentMetric(metric:String,minutes:Int):Double{val end=LocalDateTime.now();return historicalMetric(metric,end.minusMinutes(minutes.toLong()),end)}

    private fun windowMinutes(w:String)=when(w){"5m"->5;"15m"->15;"1h"->60;else->1440}
    private fun compare(v:Double,o:String,t:Double)=when(o){">"->v>t;">="->v>=t;"<"->v<t;"<="->v<=t;"=="->v==t;else->false}
    private fun actor()=(SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal)?.userId?:alertStateAbort("未认证")
}

internal fun drillPrimaryConditionIndex(evaluations:List<Map<String,Any>>):Int {
    val matched=evaluations.indexOfFirst{it["matched"]==true}
    return if(matched>=0) matched else evaluations.lastIndex.coerceAtLeast(0)
}
