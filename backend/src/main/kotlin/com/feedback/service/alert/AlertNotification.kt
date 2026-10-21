package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper

data class AlertNotificationEvent(
    val schemaVersion: Int = 1,
    val eventId: String,
    val eventType: String,
    val title: String,
    val summary: String,
    val severity: String,
    val status: String,
    val ruleCode: String,
    val ruleName: String,
    val metric: String? = null,
    val currentValue: Double? = null,
    val threshold: Double? = null,
    val window: String? = null,
    val recoveryThreshold: Double? = null,
    val instanceId: Long,
    val incidentId: Long? = null,
    val firedAt: String,
    val detailUrl: String? = null,
    val escalation: String? = null,
    val suggestedActions: List<String> = emptyList(),
    val knowledgeMatches: List<AlertKnowledgeMatch> = emptyList(),
    val adviceDegraded:Boolean=false,
    val reportId:Long?=null,
    val riskLevel:String?=null,
    val riskScore:Double?=null,
    val keyFindings:List<String> = emptyList(),
    val rcaId:Long?=null,
    val rootCause:String?=null,
    val confidence:Double?=null,
    val impactSummary:String?=null,
    val evidenceLimitations:List<String> = emptyList(),
    val environment:String?=null,
    val ownerTeam:String?=null,
    val responseSlaMinutes:Int?=null,
    val conditionDetails:List<AlertConditionDetail> = emptyList(),
    val conclusionNature:String?=null
)

data class AlertKnowledgeMatch(val id:Long,val title:String,val matchReason:String?=null,val effectivenessScore:Double?=null)
data class AlertConditionDetail(val metric:String,val value:Double,val operator:String,val threshold:Double,val windowMinutes:Int?=null,val matched:Boolean=true)

data class EmailWebhookPayload(
    val eventId: String,
    val eventType: String,
    val recipients: List<String>,
    val subject: String,
    val text: String,
    val html: String,
    val metadata: Map<String, Any?>
)

data class DeliveryResult(
    val delivered: Boolean,
    val httpStatus: Int? = null,
    val providerCode: String? = null,
    val providerMessageId: String? = null,
    val errorType: String? = null,
    val errorSummary: String? = null
) {
    companion object {
        fun success(httpStatus: Int? = null, providerCode: String? = null, providerMessageId: String? = null) =
            DeliveryResult(true, httpStatus, providerCode, providerMessageId)
        fun failure(errorType: String, summary: String, httpStatus: Int? = null, providerCode: String? = null) =
            DeliveryResult(false, httpStatus, providerCode, errorType = errorType.take(50), errorSummary = summary.take(500))
    }
}

class AlertNotificationRenderer(private val mapper: ObjectMapper) {
    fun feishu(event: AlertNotificationEvent): String = mapper.writeValueAsString(
        mapOf(
            "msg_type" to "interactive",
            "card" to mapOf(
                "header" to mapOf("title" to mapOf("tag" to "plain_text", "content" to subject(event))),
                "elements" to listOf(
                    mapOf("tag" to "div", "text" to mapOf("tag" to "lark_md", "content" to markdown(event))),
                    event.detailUrl?.let { mapOf("tag" to "action", "actions" to listOf(mapOf("tag" to "button", "text" to mapOf("tag" to "plain_text", "content" to "查看告警详情"), "url" to it, "type" to "primary"))) }
                ).filterNotNull()
            )
        )
    )

    fun email(event: AlertNotificationEvent, recipients: List<String>): EmailWebhookPayload {
        val facts = facts(event)
        val text = buildString {
            appendLine(subject(event)); appendLine(); appendLine(event.summary)
            facts.forEach { (label, value) -> appendLine("$label：$value") }
            event.detailUrl?.let { appendLine("详情：$it") }
        }.trim()
        val rows = facts.joinToString("") { (label, value) ->
            "<tr><th style=\"text-align:left;padding:6px 12px;background:#f5f7fa\">${escape(label)}</th><td style=\"padding:6px 12px\">${escape(value)}</td></tr>"
        }
        val link = event.detailUrl?.let { "<p><a href=\"${escape(it)}\">查看告警详情</a></p>" }.orEmpty()
        val html = """<!doctype html><html><body><h2>${escape(subject(event))}</h2><p>${escape(event.summary)}</p><table style="border-collapse:collapse">$rows</table>$link<p style="color:#909399">事件ID：${escape(event.eventId)}</p></body></html>"""
        return EmailWebhookPayload(event.eventId, event.eventType, recipients, subject(event), text, html,
            mapOf("severity" to event.severity, "status" to event.status, "ruleCode" to event.ruleCode,
                "instanceId" to event.instanceId, "incidentId" to event.incidentId, "schemaVersion" to event.schemaVersion))
    }

    fun subject(event: AlertNotificationEvent) = "[${event.severity}] ${event.title}".take(255)

    private fun markdown(event: AlertNotificationEvent): String = buildString {
        append("**${event.summary}**\n")
        facts(event).forEach { (label, value) -> append("\n$label：$value") }
        event.detailUrl?.let { append("\n\n[查看告警详情]($it)") }
    }

    private fun facts(event: AlertNotificationEvent): List<Pair<String, String>> = buildList {
        if(event.eventType=="RISK_BRIEF_PUBLISHED"){
            add("风险等级" to riskLabel(event.riskLevel));event.riskScore?.let{add("风险评分" to number(it))}
            if(event.keyFindings.isNotEmpty())add("重点关注" to event.keyFindings.joinToString("；"))
            event.reportId?.let{add("报告编号" to it.toString())};add("生成时间" to event.firedAt)
            return@buildList
        }
        if(event.eventType=="QUICK_RCA_READY"){
            event.rcaId?.let{add("RCA 编号" to it.toString())};event.incidentId?.let { add("Incident" to it.toString()) }
            event.rootCause?.let{add("根因线索" to it)};event.impactSummary?.let{add("影响范围" to it)}
            if(event.keyFindings.isNotEmpty())add("关键证据" to event.keyFindings.joinToString("；"))
            event.confidence?.let{add("当前置信度" to "${(it*100).toInt()}%（需人工复核）")}
            if(event.evidenceLimitations.isNotEmpty())add("证据限制" to event.evidenceLimitations.joinToString("；"))
            if(event.suggestedActions.isNotEmpty())add("下一步" to event.suggestedActions.joinToString("；"))
            event.conclusionNature?.let{add("结论性质" to it)}
            add("生成时间" to event.firedAt)
            return@buildList
        }
        add("状态" to statusLabel(event.status));event.environment?.let{add("环境" to environmentLabel(it))}
        add("触发原因" to triggerReason(event)); add("规则" to "${event.ruleName}（${event.ruleCode}）")
        if(event.conditionDetails.size>1)add("全部条件" to event.conditionDetails.joinToString("；"){conditionText(it)})
        event.metric?.let { add("监控指标" to metricLabel(it)) }; event.currentValue?.let { add("当前值" to number(it)) }
        event.threshold?.let { add("触发阈值" to number(it)) }; event.window?.let { add("统计窗口" to it) }
        event.recoveryThreshold?.let { add("恢复阈值" to number(it)) }
        add("告警实例" to event.instanceId.toString()); event.incidentId?.let { add("Incident" to it.toString()) }
        add("触发时间" to event.firedAt);event.ownerTeam?.let{add("责任团队" to teamLabel(it))};event.responseSlaMinutes?.let{add("响应要求" to "$it 分钟内确认")};event.escalation?.let { add("升级策略" to it) }
        if(event.suggestedActions.isNotEmpty())add("处置建议" to event.suggestedActions.joinToString("；"))
        if(event.knowledgeMatches.isNotEmpty())add("历史方案" to event.knowledgeMatches.joinToString("；"){"${it.title}${it.matchReason?.let{r->"（$r）"}.orEmpty()}"})
        if(event.adviceDegraded)add("AI状态" to "处置建议已降级为确定性模板")
    }

    private fun number(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else "%.4f".format(value).trimEnd('0').trimEnd('.')
    private fun statusLabel(value:String)=mapOf("FIRING" to "告警中","ACKNOWLEDGED" to "已确认","RESOLVED" to "已恢复","DIGEST" to "汇总")[value]?:value
    private fun riskLabel(value:String?)=mapOf("CRITICAL" to "严重","HIGH" to "高","MEDIUM" to "中","LOW" to "低")[value]?:"待评估"
    private fun metricLabel(value:String)=mapOf("FEEDBACK_SPIKE" to "反馈量突增","ERROR_RATE_HIGH" to "反馈错误率","SDK_REQUEST_COUNT" to "SDK 请求量","SDK_NON_RATE_LIMIT_FAILURE_RATE" to "SDK 非限流失败率","SDK_RATE_LIMIT_RATE" to "SDK 限流率","DATA_INTERRUPTION" to "数据接入中断","NOTIFICATION_FAILURE_RATE" to "通知失败率","NOTIFICATION_BACKLOG" to "通知积压量")[value]?:value
    private fun environmentLabel(value:String)=mapOf("prod" to "生产环境","production" to "生产环境","local" to "本地环境","test" to "测试环境","staging" to "预发布环境")[value.lowercase()]?:value
    private fun teamLabel(value:String)=mapOf("AI_TEAM" to "AI 平台团队","DATA_PLATFORM" to "数据平台团队")[value]?:value.replace('_',' ')
    private fun triggerReason(event:AlertNotificationEvent)=if(event.metric!=null&&event.currentValue!=null&&event.threshold!=null)"${metricLabel(event.metric)}当前值 ${number(event.currentValue)}，达到触发条件 ≥ ${number(event.threshold)}${event.window?.let{"（统计窗口 $it）"}.orEmpty()}" else event.summary
    private fun conditionText(value:AlertConditionDetail)="${metricLabel(value.metric)} ${number(value.value)} ${value.operator} ${number(value.threshold)}${value.windowMinutes?.let{"（${it}分钟）"}.orEmpty()}"
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}

fun legacyAlertEvent(mapper: ObjectMapper, content: String): AlertNotificationEvent {
    val node = mapper.readTree(content)
    val instanceId = node["instanceId"]?.asLong() ?: 0L
    val severity = node["severity"]?.asText()?.takeIf { it.isNotBlank() } ?: "P3"
    val title = node["title"]?.asText()?.takeIf { it.isNotBlank() } ?: "历史告警通知"
    return AlertNotificationEvent(
        eventId = "legacy-$instanceId", eventType = "ALERT_FIRED", title = title,
        summary = title, severity = severity, status = "FIRING", ruleCode = "LEGACY",
        ruleName = title, instanceId = instanceId, firedAt = node["firedAt"]?.asText() ?: "UNKNOWN",
        escalation = node["escalation"]?.asText()
    )
}
