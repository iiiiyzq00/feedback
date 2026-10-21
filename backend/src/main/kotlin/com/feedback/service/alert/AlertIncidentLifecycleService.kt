package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.alert.LifecycleTimelinePo
import com.feedback.repository.alert.AlertIncidentLifecycleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AlertIncidentLifecycleService(
    private val repository: AlertIncidentLifecycleRepository,
    private val mapper: ObjectMapper,
    private val entityClosure: EntityClosureService,
) {
    private val alertTransitions = mapOf(
        "PENDING" to setOf("FIRING", "CLOSED"),
        "FIRING" to setOf("ACKNOWLEDGED", "SUPPRESSED", "ESCALATED", "RECOVERED"),
        "ESCALATED" to setOf("ACKNOWLEDGED", "SUPPRESSED", "RECOVERED"),
        "ACKNOWLEDGED" to setOf("SUPPRESSED", "RECOVERED", "RESOLVED", "CLOSED"),
        "SUPPRESSED" to setOf("FIRING", "ACKNOWLEDGED", "RECOVERED", "RESOLVED"),
        "RECOVERED" to setOf("FIRING", "CLOSED"),
        "RESOLVED" to setOf("CLOSED", "ARCHIVED"),
        "CLOSED" to setOf("ARCHIVED"),
        "ARCHIVED" to emptySet(),
    )
    private val incidentTransitions = mapOf(
        "OPEN" to setOf("INVESTIGATING"),
        "INVESTIGATING" to setOf("RESOLVED"),
        "RESOLVED" to setOf("INVESTIGATING", "CLOSED"),
        "CLOSED" to setOf("ARCHIVED"),
        "ARCHIVED" to emptySet(),
    )

    /**
     * 在同一事务中锁定告警、校验状态图和版本、更新生命周期并追加不可变审计事实。
     * P0 解决必须由人工操作者提供结论，不能由自动流程绕过人工闭环。
     */
    @Transactional
    fun transitionAlert(id: Long, target: String, expectedVersion: Long, reason: String, actor: Long?, evidence: Map<String, Any?> = emptyMap()) {
        val why = reason.trim()
        ensureAlert(why.length in 2..500) { "ALERT_STATUS_REASON_REQUIRED" }
        val row = alertStateRequired(repository.lockAlert(id))
        val from = row.instanceStatus
        ensureAlert(target in (alertTransitions[from] ?: emptySet())) { "ALERT_TRANSITION_INVALID" }
        ensureAlert(row.lifecycleVersion == expectedVersion) { "ALERT_VERSION_CONFLICT" }
        if (row.instanceSeverity == "P0" && target == "RESOLVED") {
            val conclusion = evidence["conclusion"]?.toString()?.trim()
            ensureAlert(actor != null && !conclusion.isNullOrBlank() && conclusion.length >= 2) { "P0_MANUAL_CONCLUSION_REQUIRED" }
        }
        ensureAlert(repository.transitionAlert(id, target, expectedVersion, why, actor)) { "ALERT_VERSION_CONFLICT" }
        event("ALERT", id, from, target, expectedVersion + 1, why, evidence, actor)
    }

    /**
     * Incident 关闭前先关闭关联反馈；任一乐观锁或审计写入失败都会回滚整个事务。
     * 调查必须存在 commander，解决和关闭必须分别携带 resolution/closure 证据。
     */
    @Transactional
    fun transitionIncident(id: Long, target: String, expectedVersion: Long, reason: String, actor: Long, evidence: Map<String, Any?> = emptyMap()) {
        val why = reason.trim()
        ensureAlert(why.length in 2..500) { "INCIDENT_STATUS_REASON_REQUIRED" }
        val row = alertStateRequired(repository.lockIncident(id))
        val from = row.incidentStatus
        ensureAlert(target in (incidentTransitions[from] ?: emptySet())) { "INCIDENT_TRANSITION_INVALID" }
        ensureAlert(row.lifecycleVersion == expectedVersion) { "INCIDENT_VERSION_CONFLICT" }
        val commander = (evidence["commanderId"] as? Number)?.toLong()
        if (target == "INVESTIGATING") ensureAlert(row.incidentCommander != null || commander != null) { "INCIDENT_COMMANDER_REQUIRED" }
        val summary = evidence[if (target == "RESOLVED") "resolution" else "closure"]?.toString()?.trim()
        if (target in setOf("RESOLVED", "CLOSED")) {
            ensureAlert(!summary.isNullOrBlank()) { "INCIDENT_EVIDENCE_REQUIRED" }
        }
        if (target == "CLOSED") entityClosure.closeLinkedFeedback(id, actor, why)
        ensureAlert(repository.transitionIncident(id, target, expectedVersion, why, actor, summary, commander)) { "INCIDENT_VERSION_CONFLICT" }
        event("INCIDENT", id, from, target, expectedVersion + 1, why, evidence, actor)
    }

    fun timeline(type: String, id: Long): List<LifecycleTimelinePo> = repository.timeline(type, id)

    private fun event(type: String, id: Long, from: String, to: String, version: Long, reason: String, evidence: Map<String, Any?>, actor: Long?) {
        repository.appendEvent(type, id, from, to, version, reason, mapper.writeValueAsString(evidence), actor)
    }
}
