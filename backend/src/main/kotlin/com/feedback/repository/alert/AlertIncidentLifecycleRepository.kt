package com.feedback.repository.alert

import com.feedback.mapper.alert.AlertIncidentLifecycleAlertMapper
import com.feedback.mapper.alert.AlertIncidentLifecycleAuditMapper
import com.feedback.mapper.alert.AlertIncidentLifecycleIncidentMapper
import org.springframework.stereotype.Repository

@Repository
class AlertIncidentLifecycleRepository(
    private val alerts: AlertIncidentLifecycleAlertMapper,
    private val incidents: AlertIncidentLifecycleIncidentMapper,
    private val audits: AlertIncidentLifecycleAuditMapper,
) {
    fun lockAlert(id: Long) = alerts.lock(id)
    fun transitionAlert(id: Long, target: String, version: Long, reason: String, actor: Long?) = alerts.transition(id, target, version, reason, actor) == 1
    fun lockIncident(id: Long) = incidents.lock(id)
    fun transitionIncident(id: Long, target: String, version: Long, reason: String, actor: Long, summary: String?, commander: Long?) =
        incidents.transition(id, target, version, reason, actor, summary, commander) == 1
    fun timeline(type: String, id: Long) = audits.timeline(operation(type), type, id)
    fun appendEvent(type: String, id: Long, from: String, to: String, version: Long, reason: String, evidenceJson: String, actor: Long?) {
        ensureAlertRepositoryState(audits.insert(actor, operation(type), type, id.toString(), id, from, to, version, reason, evidenceJson) == 1)
    }
    private fun operation(type: String) = if (type == "ALERT") "ALERT_LIFECYCLE_TRANSITION" else "INCIDENT_LIFECYCLE_TRANSITION"
}
