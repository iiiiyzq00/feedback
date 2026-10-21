package com.feedback.repository.alert
import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
@Repository class ExternalSecurityEventRepository(private val audits:ExternalSecurityAuditMapper,
 private val alerts:ExternalSecurityAlertMapper,private val occurrences:ExternalSecurityOccurrenceMapper,
 private val incidents:ExternalSecurityIncidentMapper,private val links:ExternalSecurityLinkMapper){
 fun receipt(key:String)=audits.findReceipt(key)
 fun createAlert(rule:String,occurred:LocalDateTime):Long{val row=ExternalAlertInsertPo(ruleCode=rule,occurredAt=occurred);ensureAlertRepositoryState(alerts.insert(row)==1&&row.id>0);return row.id}
 fun occurrence(alertId:Long,evidenceJson:String,occurred:LocalDateTime){ensureAlertRepositoryState(occurrences.insert(alertId,evidenceJson,occurred)==1)}
 fun createIncident(title:String):Long{val row=ExternalIncidentInsertPo(title=title);ensureAlertRepositoryState(incidents.insert(row)==1&&row.id>0);return row.id}
 fun link(incidentId:Long,alertId:Long){ensureAlertRepositoryState(links.insert(incidentId,alertId)==1)}
 fun audit(targetId:String,detailJson:String,occurred:LocalDateTime,key:String){ensureAlertRepositoryState(audits.insertReceipt(targetId,detailJson,occurred,key)==1)}
}
