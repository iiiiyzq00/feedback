package com.feedback.service.alert

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.alert.AlertEngineRulePo
import com.feedback.repository.alert.AlertEngineRepository
import com.feedback.repository.alert.AlertMetricRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.beans.factory.annotation.Value
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

internal fun shouldResolveAlert(triggerMatched:Boolean,primaryValue:Double,recoveryThreshold:Double)=!triggerMatched&&primaryValue<recoveryThreshold
internal fun alertPrimaryConditionIndex(combinator:String,evaluations:List<Map<String,Any>>,triggerMatched:Boolean):Int {
    if(combinator=="ANY"&&triggerMatched){
        val matched=evaluations.indexOfFirst{it["matched"]==true}
        if(matched>=0)return matched
    }
    return evaluations.lastIndex.coerceAtLeast(0)
}

@Service
class AlertEngine(
    private val repository:AlertEngineRepository,
    private val metrics:AlertMetricRepository,
    private val mapper: ObjectMapper,
    private val notifications: NotificationDispatcher,
    private val escalation: NotificationEscalationService,
    private val alertRouteSkill:AlertRouteSkillService,
    private val knowledgeSearch:com.feedback.service.ai.KnowledgeSearchSkillService,
    private val lifecycle: AlertLifecycleService,
    private val stateMachine: AlertIncidentLifecycleService,
    @Value("\${app.public-base-url:}") private val publicBaseUrl: String,
    private val productionGovernance: AlertProductionGovernanceService? = null,
    private val trendBaselines:AlertTrendBaselineService,
    private val versionReleases:VersionReleaseFactService,
    private val reviewGovernance:AlertReviewGovernanceMetricService,
    private val qualityFacts:AlertQualityFactService,
    private val securitySignals:SecuritySignalService,
    @Value("\${spring.profiles.active:local}") private val environment:String
) {
    fun scheduledEvaluate() { evaluateAll() }

    @Transactional
    fun evaluateAll(): Int {
        if (!repository.acquire()) return 0
        return try {
            repository.activeRules().count { evaluateRule(it) }
        } finally {
            repository.release()
        }
    }

    private fun evaluateRule(rule:AlertEngineRulePo): Boolean {
        val ruleId = rule.id
        if(productionGovernance?.enforceRuntimeReady(ruleId)==false)return false
        val expression = mapper.readTree(rule.triggerExpression)
        // EVENT 规则读取的是持久化、已去重的事件事实，
        // 因此重启后反复评估是安全的。
        if (!effectiveNow(mapper.readTree(rule.routingConfig))) return false
        val conditions=expression.path("conditions").takeIf(JsonNode::isArray)?.toList().orEmpty().ifEmpty{listOf(expression)}
        val evaluations=conditions.map{c->val metric=c.path("metric").asText();val threshold=c.path("threshold").asDouble();val minutes=windowMinutes(c.path("window").asText());val value=metricValue(metric,minutes);mapOf("metric" to metric,"threshold" to threshold,"windowMinutes" to minutes,"value" to value,"operator" to c.path("operator").asText(">="),"matched" to compare(value,c.path("operator").asText(">="),threshold))}
        val combinator=expression.path("combinator").asText("ALL")
        val matched=if(combinator=="ANY")evaluations.any{it["matched"]==true}else evaluations.all{it["matched"]==true}
        val primaryIndex=alertPrimaryConditionIndex(combinator,evaluations,matched);val primary=evaluations[primaryIndex];val metric=primary["metric"].toString();val threshold=primary["threshold"] as Double;val value=primary["value"] as Double;val minutes=primary["windowMinutes"] as Int
        val existing = repository.openInstance(rule.ruleCode)
        if (!matched) {
            if(shouldResolveAlert(matched,value,recoveryThreshold(rule,threshold)))existing?.let { resolve(it) }
            return false
        }
        if (existing == null) {
            if (lifecycle.isSuppressed(ruleId)) return false
            if (lifecycle.inCooldown(rule.ruleCode,rule.cooldownMinutes)) return false
        }
        val instanceId = existing ?: createInstance(rule, value,evaluations,primaryIndex)
        repository.recordOccurrence(instanceId,mapper.writeValueAsString(mapOf("metric" to metric, "value" to value, "threshold" to threshold, "windowMinutes" to minutes,"conditions" to evaluations)))
        return existing == null
    }

    private fun createInstance(rule:AlertEngineRulePo, value: Double,evaluations:List<Map<String,Any>>,primaryIndex:Int): Long {
        val ruleId=rule.id;val code=rule.ruleCode;val severity=rule.defaultSeverity;val created=repository.create(ruleId,code,severity,"${rule.ruleName}（当前值 $value）");val instanceId=created.instanceId;val incidentId=created.incidentId
        val routing = mapper.readTree(rule.routingConfig)
        val channels = routing["channels"]?.map(JsonNode::asText) ?: listOf("IN_APP")
        val recipientRefs = routing.path("recipients").takeIf(JsonNode::isArray)?.map(JsonNode::asText).orEmpty()
        val expression = mapper.readTree(rule.triggerExpression)
        val recovery = rule.recoveryExpression?.let(mapper::readTree)
        val ruleName = rule.ruleName
        val primaryExpression=expression.path("conditions").takeIf(JsonNode::isArray)?.get(primaryIndex)?:expression
        val fallbackSummary="${primaryExpression.path("metric").asText()} 当前值 $value，达到触发阈值 ${primaryExpression.path("threshold").asDouble()}"
        val routeAdvice=alertRouteSkill.advise(instanceId,mapOf("instanceId" to instanceId,"incidentId" to incidentId,"ruleCode" to code,
            "ruleName" to ruleName,"severity" to severity,"metric" to primaryExpression.path("metric").asText(),"currentValue" to value,
            "threshold" to primaryExpression.path("threshold").asDouble(),"window" to primaryExpression.path("window").asText(),
            "ownerTeam" to rule.ownerTeam,"fallbackSummary" to fallbackSummary))
        val knowledge=knowledgeSearch.search("alert-$instanceId",expression.path("issueType").asText(null),
            routing.path("module").asText(null)).map{AlertKnowledgeMatch((it["id"]as Number).toLong(),it["title"].toString(),it["matchReason"]?.toString(),(it["effectivenessScore"]as? Number)?.toDouble())}
        val notificationEvent=AlertNotificationEvent(
            eventId = UUID.randomUUID().toString(), eventType = "ALERT_FIRED", title = ruleName,
            summary = routeAdvice.summary,
            severity = severity, status = "FIRING", ruleCode = code, ruleName = ruleName,
            metric = primaryExpression.path("metric").asText(), currentValue = value, threshold = primaryExpression.path("threshold").asDouble(),
            window = primaryExpression.path("window").asText(), recoveryThreshold = recovery?.get("threshold")?.asDouble(),
            instanceId = instanceId, incidentId = incidentId,
            firedAt = OffsetDateTime.now(ZoneId.systemDefault()).toString(),
            detailUrl = publicBaseUrl.trimEnd('/').takeIf(String::isNotBlank)?.let { "$it/alerts/$instanceId" },
            escalation = routing["escalation"]?.asText(),suggestedActions=routeAdvice.suggestedActions,
            knowledgeMatches=knowledge,adviceDegraded=routeAdvice.degraded,environment=environment,ownerTeam=rule.ownerTeam,
            responseSlaMinutes=when(severity){"P0"->2;"P1"->5;"P2"->15;else->60},conditionDetails=evaluations.map{AlertConditionDetail(it["metric"].toString(),(it["value"]as Number).toDouble(),it["operator"].toString(),(it["threshold"]as Number).toDouble(),it["windowMinutes"]as? Int,it["matched"]==true)}
        )
        if(severity in setOf("P0","P1")) escalation.start(notificationEvent,ruleId,rule.ownerTeam)
        else notifications.dispatch(notificationEvent, ruleId, channels, recipientRefs)
        return instanceId
    }

    private fun metricValue(metric:String,minutes:Int):Double=when(metric){
        "FEEDBACK_SPIKE"->metrics.feedbackSpike(minutes);"ERROR_RATE_HIGH"->metrics.errorRate(minutes);"CRITICAL_RATIO"->metrics.criticalRatio(minutes);"DATA_INTERRUPTION"->metrics.dataInterruption()
        "NOTIFICATION_BACKLOG"->metrics.notificationBacklog();"NOTIFICATION_FAILURE_RATE"->metrics.notificationFailureRate(minutes);"NOTIFICATION_OLDEST_PENDING"->metrics.notificationOldestPending();"NOTIFICATION_DEAD_LETTER"->metrics.notificationDeadLetters()
        "SDK_REQUEST_COUNT"->metrics.sdkCount(minutes);"SDK_FAILURE_RATE"->metrics.sdkFailureRate(minutes);"SDK_NON_RATE_LIMIT_FAILURE_RATE"->metrics.sdkNonRateLimitFailureRate(minutes);"SDK_RATE_LIMIT_RATE"->metrics.sdkRateLimitRate(minutes)
        "CHANNEL_MAX_STALE_MINUTES"->metrics.channelMaxStale();"TABLE_CHANNEL_MAX_STALE_MINUTES"->metrics.tableChannelMaxStale();"IMPORT_FAILED_BATCH_COUNT"->metrics.importFailed(minutes);"IMPORT_ROW_COUNT"->metrics.importRows(minutes);"IMPORT_FAILURE_RATE"->metrics.importFailureRate(minutes);"MAPPING_ROW_COUNT"->metrics.mappingRows(minutes);"MAPPING_ERROR_RATE"->metrics.mappingErrorRate(minutes)
        "MASKING_ATTEMPT_COUNT"->metrics.maskingAttempts(minutes);"MASKING_FAILURE_RATE"->metrics.maskingFailureRate(minutes);"MASKING_BACKLOG_COUNT"->metrics.maskingBacklog();"MASKING_OLDEST_PENDING_MINUTES"->metrics.maskingOldestPending();"QUALITY_SAMPLE_COUNT"->metrics.qualityRows(minutes);"QUALITY_SCORE_PERCENT"->metrics.qualityScore(minutes);"UNHANDLED_IDEMPOTENCY_CONFLICT_COUNT"->metrics.openIdempotencyConflicts()
        "CONTACT_ESCALATION_CANDIDATE_COUNT"->metrics.contactEscalationCandidates();"RESOLVED_REOCCURRENCE_CANDIDATE_COUNT"->metrics.resolvedReoccurrenceCandidates();"CONTENT_ERROR_SPIKE_CANDIDATE_COUNT"->metrics.contentErrorCandidates();"REDBLACK_CLUSTER_FLOOD_CANDIDATE_COUNT"->metrics.redblackFloodCandidates();"LONG_UNRESOLVED_CLUSTER_COUNT"->metrics.longUnresolvedClusters();"LONG_UNRESOLVED_JIRA_COUNT"->metrics.longUnresolvedJira();"DEVICE_PERSISTENT_FAILURE_CANDIDATE_COUNT"->metrics.devicePersistentCandidates();"URGENT_PROCESSING_SLA_BREACH_COUNT"->metrics.urgentBreaches();"FAILED_RECOVERY_SLA_BREACH_COUNT"->metrics.failedRecoveryBreaches()
        "CLUSTER_GROWTH_ANOMALY_COUNT"->trendBaselines.clusterAnomalyCount();"NEGATIVE_SENTIMENT_SURGE_INDICATOR"->trendBaselines.globalAnomaly("NEGATIVE_SENTIMENT",metrics.negativeSentimentCount(),java.time.LocalDateTime.now(),6.0,20);"SDK_VOLUME_ANOMALY_INDICATOR"->trendBaselines.globalAnomaly("SDK_VOLUME",metrics.sdkVolume30m(),java.time.LocalDateTime.now(),3.0,20)
        "VERSION_REGRESSION_CANDIDATE_COUNT"->versionReleases.regressionCandidates();"VERSION_MULTI_CHANNEL_ANOMALY_COUNT"->versionReleases.multiChannelRegressionCandidates();"VERSION_BLOCKING_REGRESSION_COUNT"->versionReleases.blockingRegressionCandidates();"HIGH_RISK_CROSS_CHANNEL_COUNT"->metrics.highRiskCrossChannel();"AI_CONSECUTIVE_FAILURE_COUNT"->metrics.aiConsecutiveFailures();"MASKING_CONSECUTIVE_FAILURE_COUNT"->metrics.maskingConsecutiveFailures()
        "CLUSTER_REVIEW_SLA_BREACH_COUNT"->reviewGovernance.clusterSlaBreaches();"MASKING_REVIEW_SLA_BREACH_COUNT"->reviewGovernance.maskingSlaBreaches();"CLUSTER_REVIEW_DECISION_COUNT"->reviewGovernance.reviewDecisionCount();"CLUSTER_MANUAL_OVERTURN_RATE"->reviewGovernance.manualOverturnRate();"RULE_INPUT_COMPLETENESS_PERCENT"->reviewGovernance.ruleInputCompleteness()
        "MASKING_LATEST_END_TO_END_F1_PERCENT"->qualityFacts.maskingF1();"MASKING_LATEST_HIGH_RISK_RECALL_PERCENT"->qualityFacts.maskingHighRiskRecall();"OPEN_INCORRECT_DEDUP_COUNT"->qualityFacts.openIncorrectDedup();"ATTACHMENT_FINAL_COUNT"->qualityFacts.attachmentFinalCount(java.time.LocalDateTime.now().minusMinutes(minutes.toLong()),java.time.LocalDateTime.now());"ATTACHMENT_FINAL_FAILURE_RATE"->qualityFacts.attachmentFailureRate(java.time.LocalDateTime.now().minusMinutes(minutes.toLong()),java.time.LocalDateTime.now())
        "OPEN_DEVICE_SAFETY_SUSPECTED_COUNT"->securitySignals.metric("DEVICE_SAFETY","SUSPECTED");"OPEN_DEVICE_SAFETY_STRONG_COUNT"->securitySignals.metric("DEVICE_SAFETY","STRONG");"OPEN_SENSITIVE_RESIDUAL_COUNT"->securitySignals.metric("SENSITIVE_RESIDUAL_BLOCKED");"OPEN_SENSITIVE_EXPOSURE_COUNT"->securitySignals.metric("SENSITIVE_EXPOSURE_CONFIRMED","CONFIRMED")
        "USER_SAFETY_IMMINENT_COUNT"->metrics.userRiskCount(minutes,"SAFETY");"USER_PRIVACY_EXPOSURE_COUNT"->metrics.userRiskCount(minutes,"PRIVACY");"DEVICE_BRICKED_AFTER_UPGRADE_COUNT"->metrics.userRiskCount(minutes,"BRICK");"USER_DATA_LOSS_IRRECOVERABLE_COUNT"->metrics.userRiskCount(minutes,"LOSS");"CHILD_CONTENT_SAFETY_COUNT"->metrics.userRiskCount(minutes,"CHILD")
        "CRITICAL_CLUSTER_SURGE_COUNT"->metrics.criticalClusterSurge();"CRITICAL_RESOLVED_RECURRENCE_COUNT"->metrics.resolvedReoccurrenceCandidates(true);"DEVICE_MODEL_WIDE_FAILURE_COUNT"->metrics.deviceModelWideFailure();"PUBLIC_NEGATIVE_FLARE_COUNT"->metrics.publicNegativeFlare();else->0.0
    }

    private fun recoveryThreshold(rule:AlertEngineRulePo,trigger:Double):Double=rule.recoveryExpression?.let{runCatching{mapper.readTree(it)["threshold"]?.asDouble()?:trigger}.getOrDefault(trigger)}?:trigger
    private fun resolve(instanceId:Long){val row=alertStateRequired(repository.state(instanceId));if(row.severity=="P0")return;var version=row.lifecycleVersion;if(row.status=="FIRING"){stateMachine.transitionAlert(instanceId,"ACKNOWLEDGED",version,"规则恢复自动确认",0,mapOf("source" to "RULE_ENGINE"));version++};stateMachine.transitionAlert(instanceId,"RESOLVED",version,"恢复阈值持续满足",0,mapOf("source" to "RULE_ENGINE","conclusion" to "指标已恢复"))}
    private fun windowMinutes(window:String)=when(window){"5m"->5;"15m"->15;"1h"->60;else->1440}
    private fun compare(v:Double,o:String,t:Double)=when(o){">"->v>t;">="->v>=t;"<"->v<t;"<="->v<=t;"=="->v==t;else->false}
    private fun effectiveNow(routing:JsonNode):Boolean{val e=routing.path("effective");if(!e.isObject)return true;return runCatching{val zone=ZoneId.of(e.path("timezone").asText("Asia/Shanghai"));val now=java.time.ZonedDateTime.now(zone);val days=e.path("days").map(JsonNode::asInt);val start=java.time.LocalTime.parse(e.path("start").asText("00:00"));val end=java.time.LocalTime.parse(e.path("end").asText("23:59"));now.dayOfWeek.value in days && !now.toLocalTime().isBefore(start) && !now.toLocalTime().isAfter(end)}.getOrDefault(false)}
}
