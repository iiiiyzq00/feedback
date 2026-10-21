package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

data class AlertSuppressionView(val row:AlertSuppressionPo,val suppressedByName:String?)
@Repository class AlertLifecycleRepository(
 private val notifications:AlertLifecycleNotificationMapper,private val rules:AlertLifecycleRuleMapper,
 private val catalog:AlertLifecycleCatalogMapper,private val instances:AlertLifecycleInstanceMapper,
 private val links:AlertLifecycleIncidentLinkMapper,private val users:AlertLifecycleUserMapper){
 fun activeSuppressions()=notifications.activeSuppressions()
 fun suppressions():List<AlertSuppressionView>{val rows=notifications.suppressions();val ids=rows.mapNotNull{it.suppressedBy}.distinct();val names=if(ids.isEmpty())emptyMap() else users.names(ids).associate{it.id to it.displayName};return rows.map{AlertSuppressionView(it,it.suppressedBy?.let(names::get))}}
 fun rule(id:Long)=rules.findById(id)
 fun policies(codes:Collection<String>):List<AlertCatalogPolicyPo>{val unique=codes.distinct();return if(unique.isEmpty())emptyList() else catalog.policies(unique)}
 fun overlaps(start:LocalDateTime,end:LocalDateTime,ruleId:Long?)=notifications.overlaps(start,end,ruleId)>0
 fun createSuppression(row:AlertSuppressionInsertPo):Long{ensureAlertRepositoryState(notifications.insertSuppression(row)==1&&row.id>0);return row.id}
 fun deleteFuture(id:Long)=notifications.deleteFuture(id)==1
 fun inCooldown(code:String,minutes:Int)=instances.cooldownCount(code,minutes)>0
 fun pageOpen(afterId:Long,limit:Int)=instances.pageOpen(afterId,limit)
 fun activeRules(codes:Collection<String>):List<AlertLifecycleRulePo>{val unique=codes.distinct();return if(unique.isEmpty())emptyList() else rules.activeByCodes(unique)}
 fun escalationMarkers(ids:Collection<Long>):List<AlertEscalationMarkerPo>{val unique=ids.distinct();return if(unique.isEmpty())emptyList() else notifications.escalationMarkers(unique)}
 fun latestIncidents(ids:Collection<Long>):Map<Long,Long>{val unique=ids.distinct();return if(unique.isEmpty())emptyMap() else links.latest(unique).associate{it.alertInstanceId to it.incidentId}}
 fun updateSeverity(ids:Collection<Long>,target:String){val unique=ids.distinct();if(unique.isNotEmpty())ensureAlertRepositoryState(instances.updateSeverity(unique,target)==unique.size){"ALERT_INSTANCE_STATE_CONFLICT:SEVERITY:$target"}}
 fun insertEscalationNotifications(rows:List<AlertEscalationNotificationInsertPo>){if(rows.isNotEmpty())ensureAlertRepositoryState(notifications.insertEscalationNotifications(rows)==rows.size)}
}
