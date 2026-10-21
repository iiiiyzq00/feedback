package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.alert.AlertEscalationNotificationInsertPo
import com.feedback.model.po.alert.AlertSuppressionInsertPo
import com.feedback.repository.alert.AlertLifecycleRepository
import com.feedback.security.WebPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

data class SilenceInput(val type: String, val ruleId: Long? = null, val start: OffsetDateTime, val end: OffsetDateTime, val reason: String)

@Service
class AlertLifecycleService(
    private val repository: AlertLifecycleRepository,
    private val mapper: ObjectMapper,
    @Suppress("unused") private val notifications: NotificationDispatcher,
    private val escalation: NotificationEscalationService,
) {
    fun isSuppressed(ruleId: Long): Boolean {
        val suppressions = repository.activeSuppressions().map { it to mapper.readTree(it.scope) }
        val ruleSpecific = suppressions.any { (_, scope) -> !scope.path("all").asBoolean(false) && scope.path("ruleId").asLong(Long.MIN_VALUE) == ruleId }
        if (ruleSpecific) return true
        if (suppressions.none { (_, scope) -> scope.path("all").asBoolean(false) }) return false
        val rule = repository.rule(ruleId) ?: return true
        val p0NonSilenceable = repository.policies(listOf(rule.ruleCode)).any {
            it.ruleCode == rule.ruleCode && it.defaultSeverity == "P0" && it.p0NonSilenceable
        }
        return !p0NonSilenceable
    }

    fun inCooldown(ruleCode: String, cooldown: Int): Boolean = cooldown > 0 && repository.inCooldown(ruleCode, cooldown)

    fun listSilences(): List<Map<String, Any?>> = repository.suppressions().map { view ->
        linkedMapOf(
            "id" to view.row.id, "type" to view.row.type, "scope" to view.row.scope,
            "start" to view.row.start, "end" to view.row.end, "createdAt" to view.row.createdAt,
            "suppressedBy" to view.row.suppressedBy, "suppressedByName" to view.suppressedByName,
        )
    }

    fun createSilence(input: SilenceInput): Map<String, Any> {
        ensureAlert(input.type in setOf("MAINTENANCE", "MANUAL", "DUPLICATE"))
        ensureAlert(input.start.isBefore(input.end))
        ensureAlert(input.end.isAfter(OffsetDateTime.now()))
        ensureAlert(input.reason.isNotBlank() && input.reason.length <= 200)
        ensureAlert(input.ruleId != null || input.type == "MAINTENANCE") { "非维护静默必须指定规则" }
        input.ruleId?.let { ruleId ->
            val rule = repository.rule(ruleId)
            val p0 = rule != null && repository.policies(listOf(rule.ruleCode)).any {
                it.ruleCode == rule.ruleCode && it.defaultSeverity == "P0" && it.p0NonSilenceable
            }
            ensureAlert(!p0) { "P0安全规则不可静默" }
            ensureAlert(rule?.versionStatus == "ACTIVE") { "规则不存在" }
        }
        val actor = actor()
        val scope = mapper.writeValueAsString(
            if (input.ruleId == null) mapOf("all" to true, "reason" to input.reason)
            else mapOf("ruleId" to input.ruleId, "reason" to input.reason),
        )
        val zone = ZoneId.systemDefault()
        val start = input.start.atZoneSameInstant(zone).toLocalDateTime()
        val end = input.end.atZoneSameInstant(zone).toLocalDateTime()
        ensureAlert(!repository.overlaps(start, end, input.ruleId)) { "静默时间范围重叠" }
        val id = repository.createSuppression(AlertSuppressionInsertPo(type=input.type,scopeJson=scope,start=start,end=end,actor=actor))
        return mapOf("id" to id)
    }

    fun deleteSilence(id: Long) {
        if (!repository.deleteFuture(id)) alertAbort("只能删除尚未生效的静默")
    }

    /**
     * 按告警主表稳定分页，当前页再批量装配活动规则、升级标记和 Incident；避免原实现逐告警查库。
     * 严重度更新、升级链联动和站内升级事实处于同一事务，任一步失败均整体回滚。
     */
    @Transactional
    fun escalate(): Int {
        var afterId = 0L
        var count = 0
        while (true) {
            val candidates = repository.pageOpen(afterId, 500)
            if (candidates.isEmpty()) break
            afterId = candidates.last().id
            val rulesByCode = repository.activeRules(candidates.map { it.ruleCode }).groupBy { it.ruleCode }
            ensureAlertState(rulesByCode.values.none { it.size > 1 }) { "ALERT_ACTIVE_RULE_DUPLICATED" }
            val markers = repository.escalationMarkers(candidates.map { it.id }).groupBy { it.alertInstanceId }
            val now = LocalDateTime.now()
            val due = candidates.mapNotNull { candidate ->
                val rule = rulesByCode[candidate.ruleCode]?.singleOrNull() ?: return@mapNotNull null
                val policy = mapper.readTree(rule.routingConfig)["escalation"]?.asText() ?: "NONE"
                val (minutes, target) = when (policy) {
                    "30M_TO_P1" -> 30L to "P1"
                    "1H_TO_P1" -> 60L to "P1"
                    "2H_TO_P0" -> 120L to "P0"
                    else -> return@mapNotNull null
                }
                if (candidate.firedAt.isAfter(now.minusMinutes(minutes))) return@mapNotNull null
                val marker = "\"escalation\":\"$target\""
                if (markers[candidate.id].orEmpty().any { it.notifyContent?.contains(marker) == true }) return@mapNotNull null
                Triple(candidate, rule, target)
            }
            val incidents = repository.latestIncidents(due.map { it.first.id })
            due.groupBy { it.third }.forEach { (target, rows) -> repository.updateSeverity(rows.map { it.first.id }, target) }
            val notificationRows = due.map { (candidate, rule, target) ->
                escalation.reflectSeverityUpgrade(candidate.id, target, candidate.ruleId, candidate.ruleCode, rule.ruleName, rule.ownerTeam, incidents[candidate.id])
                val content = mapper.writeValueAsString(mapOf("title" to "${rule.ruleName} 已升级", "severity" to target, "instanceId" to candidate.id, "escalation" to target))
                AlertEscalationNotificationInsertPo(candidate.ruleId, candidate.ruleCode, candidate.id, content)
            }
            repository.insertEscalationNotifications(notificationRows)
            count += due.size
            if (candidates.size < 500) break
        }
        return count
    }

    private fun actor() = (SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal)?.userId ?: alertStateAbort("未认证")
}
