package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository

data class CreatedAlertIncident(val instanceId:Long,val incidentId:Long)

@Repository
class AlertEngineRepository(private val locks:AlertEngineLockMapper,private val rules:AlertEngineRuleMapper,private val instances:AlertEngineInstanceMapper,private val incidents:AlertEngineIncidentMapper,private val links:AlertEngineIncidentLinkMapper,private val occurrences:AlertEngineOccurrenceMapper,private val clusterLinks:AlertEngineClusterLinkMapper){
 fun acquire()=locks.acquire()==1
 fun release(){locks.release()}
 fun activeRules()=rules.active()
 fun openInstance(code:String)=instances.findOpen(code)
 fun recordOccurrence(instance:Long,snapshot:String){ensureAlertRepositoryState(occurrences.insert(instance,snapshot)==1)}
 fun create(ruleId:Long,code:String,severity:String,title:String):CreatedAlertIncident{val alert=AlertEngineInstanceInsertPo(ruleId=ruleId,ruleCode=code,severity=severity);ensureAlertRepositoryState(instances.insert(alert)==1);val incident=AlertEngineIncidentInsertPo(title=title,severity=severity);ensureAlertRepositoryState(incidents.insert(incident)==1);val alertId=alertRepositoryStateRequired(alert.id);val incidentId=alertRepositoryStateRequired(incident.id);ensureAlertRepositoryState(links.insert(incidentId,alertId)==1);return CreatedAlertIncident(alertId,incidentId)}
 fun activeIncident(instance:Long)=links.activeIncident(instance)
 fun state(instance:Long)=instances.state(instance)
 fun clusterSize(cluster:Long)=clusterLinks.activeCount(cluster)
}
