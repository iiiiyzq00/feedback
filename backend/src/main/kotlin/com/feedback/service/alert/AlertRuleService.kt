package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.feedback.model.po.alert.AlertGovernanceCatalogPo
import com.feedback.model.po.alert.AlertRuleInsertPo
import com.feedback.model.po.alert.AlertRuleListPo
import com.feedback.model.po.alert.AlertRuleTestInsertPo
import com.feedback.repository.alert.AlertRuleRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.feedback.security.WebPrincipal

data class AlertRuleInput(
    val name: String, val level: String, val metric: String, val threshold: Double,
    val window: String, val channels: List<String> = listOf("inapp"), val escalation: String = "NONE",
    val cooldownMinutes: Int = 60, val recoveryThreshold: Double? = null,
    val conditions: List<AlertConditionInput> = emptyList(), val combinator: String = "ALL",
    val ownerTeam: String? = null, val recipients: List<String> = emptyList(),
    val effectiveDays: List<Int> = (1..7).toList(), val effectiveStart: String = "00:00",
    val effectiveEnd: String = "23:59", val timezone: String = "Asia/Shanghai"
)
data class AlertConditionInput(val metric:String,val operator:String=">=",val threshold:Double,val window:String)
data class ReorderRulesInput(val ruleIds:List<Long>)
data class TestRuleInput(val rule:AlertRuleInput,val metricValues:Map<String,Double>,val ruleId:Long?=null,val mode:String="SIMULATION")

@Service
class AlertRuleService(private val repository:AlertRuleRepository, private val mapper: ObjectMapper,private val notificationPolicy:NotificationPolicy,
    private val productionGovernance:AlertProductionGovernanceService) {
    fun governanceCatalog(): Map<String, Any> {
        val expected = repository.expectedCount()
        val rules = repository.catalog().map(::catalogView)
        val consistent = rules.size == expected
        return mapOf(
            "rules" to rules,
            "summary" to mapOf(
                "total" to rules.size,
                "expected" to expected,
                "consistent" to consistent,
                "implemented" to rules.count { it["implementationStatus"] == "IMPLEMENTED" },
                "contractRequired" to rules.count { it["implementationStatus"] == "CONTRACT_REQUIRED" },
                "external" to rules.count { it["implementationStatus"] == "EXTERNAL" }
            ),
            "activationPolicy" to (if (consistent) "只有 APPROVED + IMPLEMENTED 的目录规则允许启用；EXTERNAL 由 Alertmanager/SIEM 接入 Incident"
                else "规则目录数量与治理基线不一致，请先完成目录治理；当前目录仍可查看，但不得据此放宽生产启用条件")
        )
    }

    fun list(): List<Map<String, Any?>> = repository.list().map{(row,last)->view(ruleMap(row,last))}

    @Transactional
    fun create(input: AlertRuleInput): Long {
        validate(input)
        val code = "RULE_${System.currentTimeMillis()}"
        val p=params(input)
        // 通用草稿被刻意排除在封闭的 34 条规则治理目录之外，
        // 因此无法通过生产启用校验。
        return repository.insert(AlertRuleInsertPo(ruleCode=code,name=p["name"] as String,ownerTeam=p["owner"] as String?,priority=repository.nextPriority(),version="v1",level=p["level"] as String,triggerExpression=p["expression"] as String,recoveryExpression=p["recovery"] as String,routingConfig=p["routing"] as String,cooldownMinutes=p["cooldown"] as Int,enabled=false))
    }

    @Transactional
    fun update(id: Long, input: AlertRuleInput): Long {
        validate(input)
        val old = alertStateRequired(repository.lockActive(id));val next = "v${old.version.removePrefix("v").toIntOrNull()?.plus(1) ?: 2}";repository.retire(id);val p=params(input)
        return repository.insert(AlertRuleInsertPo(ruleCode=old.ruleCode,name=p["name"] as String,ownerTeam=p["owner"] as String?,priority=old.priority,version=next,level=p["level"] as String,triggerExpression=p["expression"] as String,recoveryExpression=p["recovery"] as String,routingConfig=p["routing"] as String,cooldownMinutes=p["cooldown"] as Int,enabled=old.enabled))
    }

    fun toggle(id: Long, enabled: Boolean) {
        if (enabled) {
            val gaps=productionGovernance.activationGaps(id)
            ensureAlert(gaps["readyToPublish"]==true){"ALERT_RULE_NOT_PRODUCTION_READY"}
        }
        if (!repository.toggle(id,enabled)) alertMissing("ALERT_RULE_NOT_FOUND")
    }

    @Transactional fun reorder(input:ReorderRulesInput):Map<String,Any>{ensureAlert(input.ruleIds.isNotEmpty()&&input.ruleIds.distinct().size==input.ruleIds.size);repository.reorder(input.ruleIds);return mapOf("ordered" to input.ruleIds.size)}

    @Transactional
    fun test(input:TestRuleInput):Map<String,Any?>{
        ensureAlert(input.mode=="SIMULATION"){"该接口只接受SIMULATION；历史回放、影子运行和演练使用各自的隔离入口"};validate(input.rule)
        val actor=(SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal)?.userId?:alertStateAbort("未认证")
        val conditions=effectiveConditions(input.rule);val results=conditions.map{c->val value=input.metricValues[c.metric]?:0.0;mapOf("metric" to c.metric,"value" to value,"operator" to c.operator,"threshold" to c.threshold,"matched" to compare(value,c.operator,c.threshold))};val matched=if(input.rule.combinator=="ANY")results.any{it["matched"]==true}else results.all{it["matched"]==true}
        val route=mapOf("ownerTeam" to input.rule.ownerTeam,"channels" to input.rule.channels,"externalNotificationsSent" to 0,"deliveryPolicy" to "TEST_ISOLATED")
        val result=linkedMapOf<String,Any?>("mode" to "SIMULATION","matched" to matched,"combinator" to input.rule.combinator,"conditions" to results,"channels" to input.rule.channels,"ownerTeam" to input.rule.ownerTeam,"sampleSize" to input.metricValues.size,"route" to route,"externalNotificationsSent" to 0)
        input.ruleId?.let(repository::ruleCode)
        result["runId"]=repository.insertTest(AlertRuleTestInsertPo(ruleId=input.ruleId,matched=matched,inputJson=mapper.writeValueAsString(input.metricValues),resultJson=mapper.writeValueAsString(result),sampleSize=input.metricValues.size,actor=actor));return result
    }

    fun testRuns(limit:Int):List<Map<String,Any?>>{ensureAlert(limit in 1..200);return repository.testRuns(limit).map{linkedMapOf("id" to it.id,"ruleId" to it.ruleId,"ruleCode" to it.ruleCode,"ruleName" to it.ruleName,"mode" to it.mode,"status" to it.status,"matched" to it.matched,"sampleSize" to it.sampleSize,"externalNotificationsSent" to it.externalNotificationsSent,"createdAt" to it.createdAt,"completedAt" to it.completedAt,"createdByName" to it.createdByName)}}

    private fun params(input: AlertRuleInput):Map<String,Any?> { val (resolvedChannels,policy)=notificationPolicy.resolve(input.level,input.channels);return mapOf(
        "name" to input.name.trim(), "level" to input.level,
        "expression" to mapper.writeValueAsString(mapOf("metric" to input.metric, "threshold" to input.threshold, "window" to input.window,"combinator" to input.combinator,"conditions" to effectiveConditions(input))),
        "recovery" to mapper.writeValueAsString(mapOf("threshold" to (input.recoveryThreshold ?: input.threshold))),
        "cooldown" to input.cooldownMinutes,
        "owner" to input.ownerTeam?.trim(),
        "routing" to mapper.writeValueAsString(mapOf("channels" to resolvedChannels,"notificationPolicyVersion" to policy.version,"deliveryMode" to policy.mode,"escalation" to input.escalation,"recipients" to input.recipients.distinct(),"effective" to mapOf("days" to input.effectiveDays,"start" to input.effectiveStart,"end" to input.effectiveEnd,"timezone" to input.timezone)))
    )}
    private fun validate(input: AlertRuleInput) {
        ensureAlert(input.name.isNotBlank() && input.name.length <= 200)
        ensureAlert(input.level in setOf("P0", "P1", "P2", "P3"))
        ensureAlert(input.metric in metrics)
        ensureAlert(input.threshold >= 0 && input.window in setOf("5m", "15m", "1h", "24h"))
        ensureAlert(input.channels.all { it.lowercase() in setOf("feishu","feishu_card","feishu_all","feishu_dm","feishu_task","email","sms","voice_call","inapp","in_app") })
        notificationPolicy.resolve(input.level,input.channels)
        ensureAlert(input.escalation in setOf("NONE","30M_TO_P1","1H_TO_P1","2H_TO_P0"))
        ensureAlert(input.cooldownMinutes in 0..10080)
        ensureAlert(input.recoveryThreshold == null || input.recoveryThreshold in 0.0..input.threshold)
        ensureAlert(input.combinator in setOf("ALL","ANY") && effectiveConditions(input).size in 1..5)
        effectiveConditions(input).forEach{ensureAlert(it.metric in metrics || it.metric.startsWith("OPEN_") || it.metric.startsWith("ATTACHMENT_") || it.metric.startsWith("MASKING_LATEST_") || it.metric.startsWith("CLUSTER_REVIEW_") || it.metric.startsWith("RULE_INPUT_") || it.metric=="CLUSTER_MANUAL_OVERTURN_RATE");ensureAlert(it.operator in setOf(">",">=","<","<=","=="));ensureAlert(it.threshold>=0);ensureAlert(it.window in setOf("5m","15m","1h","24h"))}
        ensureAlert(input.ownerTeam==null||(input.ownerTeam.isNotBlank()&&input.ownerTeam.length<=50));ensureAlert(input.recipients.size<=50&&input.recipients.all{it.length in 1..100})
        ensureAlert(input.effectiveDays.isNotEmpty()&&input.effectiveDays.all{it in 1..7});ensureAlert(input.effectiveStart.matches(Regex("^([01]\\d|2[0-3]):[0-5]\\d$"))&&input.effectiveEnd.matches(Regex("^([01]\\d|2[0-3]):[0-5]\\d$")));runCatching{java.time.ZoneId.of(input.timezone)}.getOrElse{alertAbort("时区非法")}
    }

    private fun view(row: Map<String, Any?>): Map<String, Any?> {
        val expression = parseObject(row["triggerExpression"])
        val recovery = parseObject(row["recoveryExpression"])
        val routing = parseObject(row["routingConfig"])
        val conditionNodes=expression.path("conditions").takeIf(JsonNode::isArray)?.toList().orEmpty()
        val primary=conditionNodes.firstOrNull{it.path("metric").asText()!="SDK_REQUEST_COUNT"}?:conditionNodes.firstOrNull()
        val metric = expression.path("metric").asText().ifBlank{primary?.path("metric")?.asText().orEmpty()}.ifBlank{"未配置指标"}
        val threshold = expression.path("threshold").takeUnless(JsonNode::isMissingNode)?.asDouble() ?: primary?.path("threshold")?.asDouble() ?: 0.0
        val window = expression.path("window").asText().ifBlank{primary?.path("window")?.asText().orEmpty()}.ifBlank{"未配置窗口"}
        val channels = routing.path("channels").takeIf(JsonNode::isArray)
            ?.mapNotNull { it.asText().takeIf(String::isNotBlank)?.lowercase() }
            ?.takeIf(List<String>::isNotEmpty) ?: listOf("inapp")
        return LinkedHashMap(row).apply {
            remove("triggerExpression"); remove("recoveryExpression"); remove("routingConfig")
            this["metric"] = metric; this["threshold"] = threshold
            this["window"] = window; this["channels"] = channels
            this["escalation"] = routing["escalation"]?.asText() ?: "NONE"
            this["recipients"] = routing.path("recipients").takeIf(JsonNode::isArray)?.map(JsonNode::asText).orEmpty()
            val effective=routing.path("effective");this["effectiveDays"]=effective.path("days").takeIf(JsonNode::isArray)?.map(JsonNode::asInt)?: (1..7).toList();this["effectiveStart"]=effective.path("start").asText("00:00");this["effectiveEnd"]=effective.path("end").asText("23:59");this["timezone"]=effective.path("timezone").asText("Asia/Shanghai")
            this["conditions"] = conditionNodes.map{mapper.convertValue(it,Map::class.java)}
            this["combinator"] = expression.path("combinator").asText("ALL")
            this["recoveryThreshold"] = recovery?.get("threshold")?.asDouble()
            this["cooldownMinutes"] = row["cooldown_minutes"]
            this["conditionDesc"] = if(metric=="EVENT_ONLY") "事件触发 · 接入处理失败且重试耗尽（无需时间窗口和数值阈值）" else if(conditionNodes.isEmpty()) "$metric · $window · 阈值 $threshold" else conditionNodes.joinToString(" ${if(expression.path("combinator").asText("ALL")=="ANY") "或" else "且"} "){c->"${metricLabel(c.path("metric").asText())} ${c.path("window").asText()} ${c.path("operator").asText(">=")} ${c.path("threshold").asDouble()}"}
        }
    }

    private val metrics=setOf("FEEDBACK_SPIKE","ERROR_RATE_HIGH","CRITICAL_RATIO","DATA_INTERRUPTION","NOTIFICATION_BACKLOG","NOTIFICATION_FAILURE_RATE","NOTIFICATION_OLDEST_PENDING","NOTIFICATION_DEAD_LETTER","SDK_REQUEST_COUNT","SDK_FAILURE_RATE","SDK_NON_RATE_LIMIT_FAILURE_RATE","SDK_RATE_LIMIT_RATE","CHANNEL_MAX_STALE_MINUTES","TABLE_CHANNEL_MAX_STALE_MINUTES","IMPORT_FAILED_BATCH_COUNT","IMPORT_ROW_COUNT","IMPORT_FAILURE_RATE","MAPPING_ROW_COUNT","MAPPING_ERROR_RATE","MASKING_ATTEMPT_COUNT","MASKING_FAILURE_RATE","MASKING_BACKLOG_COUNT","MASKING_OLDEST_PENDING_MINUTES","QUALITY_SAMPLE_COUNT","QUALITY_SCORE_PERCENT","UNHANDLED_IDEMPOTENCY_CONFLICT_COUNT","CONTACT_ESCALATION_CANDIDATE_COUNT","RESOLVED_REOCCURRENCE_CANDIDATE_COUNT","CONTENT_ERROR_SPIKE_CANDIDATE_COUNT","REDBLACK_CLUSTER_FLOOD_CANDIDATE_COUNT","LONG_UNRESOLVED_CLUSTER_COUNT","LONG_UNRESOLVED_JIRA_COUNT","DEVICE_PERSISTENT_FAILURE_CANDIDATE_COUNT","URGENT_PROCESSING_SLA_BREACH_COUNT","FAILED_RECOVERY_SLA_BREACH_COUNT","CLUSTER_GROWTH_ANOMALY_COUNT","NEGATIVE_SENTIMENT_SURGE_INDICATOR","SDK_VOLUME_ANOMALY_INDICATOR","VERSION_REGRESSION_CANDIDATE_COUNT","VERSION_MULTI_CHANNEL_ANOMALY_COUNT","HIGH_RISK_CROSS_CHANNEL_COUNT","AI_CONSECUTIVE_FAILURE_COUNT","MASKING_CONSECUTIVE_FAILURE_COUNT","OPEN_DEVICE_SAFETY_SUSPECTED_COUNT","OPEN_DEVICE_SAFETY_STRONG_COUNT","OPEN_SENSITIVE_RESIDUAL_COUNT","OPEN_SENSITIVE_EXPOSURE_COUNT","USER_SAFETY_IMMINENT_COUNT","USER_PRIVACY_EXPOSURE_COUNT","DEVICE_BRICKED_AFTER_UPGRADE_COUNT","USER_DATA_LOSS_IRRECOVERABLE_COUNT","CHILD_CONTENT_SAFETY_COUNT","VERSION_BLOCKING_REGRESSION_COUNT","CRITICAL_CLUSTER_SURGE_COUNT","CRITICAL_RESOLVED_RECURRENCE_COUNT","DEVICE_MODEL_WIDE_FAILURE_COUNT","PUBLIC_NEGATIVE_FLARE_COUNT")
    private fun metricLabel(metric:String)=mapOf("SDK_REQUEST_COUNT" to "SDK请求数","SDK_NON_RATE_LIMIT_FAILURE_RATE" to "SDK非限流失败率(%)","SDK_RATE_LIMIT_RATE" to "SDK限流率(%)","NOTIFICATION_BACKLOG" to "通知待投递数","NOTIFICATION_FAILURE_RATE" to "通知失败率(%)","NOTIFICATION_OLDEST_PENDING" to "最老待投递时长(秒)","NOTIFICATION_DEAD_LETTER" to "通知死信数","EVENT_ONLY" to "事件触发")[metric]?:metric
    private fun effectiveConditions(i:AlertRuleInput)=i.conditions.ifEmpty{listOf(AlertConditionInput(i.metric,">=",i.threshold,i.window))}
    private fun compare(v:Double,o:String,t:Double)=when(o){">"->v>t;">="->v>=t;"<"->v<t;"<="->v<=t;else->v==t}
    private fun catalogView(row:AlertGovernanceCatalogPo)=linkedMapOf<String,Any?>("ruleCode" to row.ruleCode,"ruleName" to row.ruleName,"category" to row.category,"defaultSeverity" to row.defaultSeverity,"ownerTeam" to row.ownerTeam,"evaluationMode" to row.evaluationMode,"implementationStatus" to row.implementationStatus,"governanceStatus" to row.governanceStatus,"parameterTemplate" to mapper.readTree(row.parameterTemplate.toString()),"recoveryPolicy" to row.recoveryPolicy,"dataDependencies" to mapper.readTree(row.dataDependencies.toString()),"p0NonSilenceable" to row.p0NonSilenceable,"sourceReference" to row.sourceReference,"preventionClass" to row.preventionClass,"preventionMeasures" to (row.preventionMeasures?.takeIf(String::isNotBlank)?.let(mapper::readTree)?:mapper.createArrayNode()),"automatedGates" to (row.automatedGates?.takeIf(String::isNotBlank)?.let(mapper::readTree)?:mapper.createArrayNode()))
    private fun ruleMap(row:AlertRuleListPo,last:java.time.LocalDateTime?)=linkedMapOf<String,Any?>("id" to row.id,"ruleCode" to row.ruleCode,"name" to row.name,"category" to row.category,"level" to row.level,"enabled" to row.enabled,"versionStatus" to row.versionStatus,"triggerExpression" to row.triggerExpression,"recoveryExpression" to row.recoveryExpression,"routingConfig" to row.routingConfig,"cooldown_minutes" to row.cooldownMinutes,"version" to row.version,"ownerTeam" to row.ownerTeam,"priority" to row.priority,"lastTriggeredAt" to last)

    private fun parseObject(value: Any?): JsonNode = try {
        value?.toString()?.takeIf(String::isNotBlank)?.let(mapper::readTree)
            ?.takeIf(JsonNode::isObject) ?: mapper.createObjectNode()
    } catch (_: Exception) {
        mapper.createObjectNode()
    }
}
