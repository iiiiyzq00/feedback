package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository

data class EscalationTargetView(val type:String,val userId:Long,val name:String)

@Repository class NotificationEscalationRepository(private val notifications:NotificationEscalationMapper,private val configs:EscalationConfigMapper,private val users:EscalationUserMapper,private val rules:EscalationRuleMapper,private val occurrences:EscalationOccurrenceMapper,private val incidents:EscalationIncidentMapper,private val audits:EscalationAuditMapper){
 fun ensureChain(row:EscalationChainInsertPo)=notifications.insertChain(row)
 fun chainByAlert(alert:Long,runningOnly:Boolean=false)=notifications.chainByAlert(alert,runningOnly)
 fun chain(id:Long)=notifications.chainByDisplay(id)
 fun chainInternal(id:Long)=notifications.chainByInternal(id)
 fun hasTargets(chain:Long)=notifications.targetCount(chain)>0
 fun saveTargets(rows:List<EscalationTargetInsertPo>){if(rows.isNotEmpty())ensureAlertRepositoryState(notifications.insertTargets(rows)==rows.size)}
 fun resolveTargets(team:String):List<EscalationTargetView>{val configured=configs.targets(team);if(configured.isEmpty())return emptyList();val userMap=users.active(configured.map{it.userId}.distinct()).associateBy{it.id};val seen=mutableSetOf<Long>();return configured.sortedWith(compareBy<EscalationConfigTargetPo>{it.ordering}.thenBy{it.userId}).mapNotNull{row->val user=userMap[row.userId]?:return@mapNotNull null;if(!seen.add(row.userId))null else EscalationTargetView(row.type,row.userId,user.displayName?:"用户${row.userId}")}}
 fun updateSeverity(alert:Long,severity:String)=notifications.updateSeverity(alert,severity)==1
 fun updateIncidentSeverity(id:Long,severity:String){ensureAlertRepositoryState(incidents.severity(id,severity)==1){"INCIDENT_STATE_CONFLICT:$id:SEVERITY"}}
 fun escalationExists(alert:Long,channel:String)=notifications.escalationExists(alert,channel)>0
 fun insertEscalation(row:EscalationNotificationInsertPo){ensureAlertRepositoryState(notifications.insertEscalation(row)==1)}
 fun insertBaseline(rows:List<EscalationNotificationInsertPo>){if(rows.isNotEmpty())notifications.insertBaseline(rows)}
 fun baselineCount(alert:Long,channels:List<String>)=notifications.baselineCount(alert,channels)
 fun firstContent(chain:Long)=notifications.firstContent(chain)
 fun ruleName(id:Long)=rules.find(id)?.name
 fun currentValue(instance:Long)=occurrences.latest(instance)?.currentValue
 fun insertStandalone(row:EscalationNotificationInsertPo){ensureAlertRepositoryState(notifications.insertStandalone(row)==1)}
 fun target(chain:Long,order:Int)=notifications.target(chain,order)
 fun targetByDisplay(id:Long)=notifications.targetByDisplay(id)
 fun targetCount(chain:Long)=notifications.targetCount(chain)
 fun markTargetProgress(id:Long){ensureAlertRepositoryState(notifications.markTargetProgress(id)==1){"ESCALATION_TARGET_STATE_CONFLICT:$id:IN_PROGRESS"}}
 fun baselineExists(alert:Long,channel:String)=notifications.baselineExists(alert,channel)>0
 fun updateDeadline(chain:EscalationChainPo,seconds:Int){ensureAlertRepositoryState(notifications.updateDeadline(chain.id,chain.targetOrder,chain.channelOrder,seconds)==1){"ESCALATION_CHAIN_STATE_CONFLICT:${chain.displayId}:DEADLINE"}}
 fun insertCurrent(row:EscalationNotificationInsertPo){ensureAlertRepositoryState(notifications.insertEscalationNotification(row)==1)}
 fun notification(id:Long)=notifications.notification(id)
 fun advanceIfCurrent(id:Long,target:Int,channel:Int,reason:String)=notifications.advanceIfCurrent(id,target,channel,reason)==1
 fun acknowledgeChain(id:Long,user:Long?)=notifications.acknowledgeChain(id,user)==1
 fun acknowledgeTarget(id:Long){ensureAlertRepositoryState(notifications.acknowledgeTarget(id)==1){"ESCALATION_TARGET_STATE_CONFLICT:$id:ACKNOWLEDGED"}}
 fun acknowledgeNotification(id:Long,type:String){ensureAlertRepositoryState(notifications.acknowledgeNotification(id,type)==1){"ESCALATION_NOTIFICATION_STATE_CONFLICT:$id:ACKNOWLEDGED"}}
 fun acknowledgeAlert(alert:Long,actor:Long)=notifications.acknowledgeAlert(alert,actor)==1
 fun cancelAlert(alert:Long)=notifications.cancelAlert(alert)==1
 fun timedOut()=notifications.timedOut();fun advanceTimedOut(row:EscalationTimeoutPo,reason:String)=notifications.advanceTimedOut(row,reason)==1
 fun advanceTarget(chain:EscalationChainPo,reason:String):Boolean{if(notifications.advanceTarget(chain,reason)==0)return false;ensureAlertRepositoryState(notifications.failTarget(chain.id,chain.targetOrder)==1){"ESCALATION_TARGET_STATE_CONFLICT:${chain.displayId}:${chain.targetOrder}"};return true}
 fun exhaust(chain:EscalationChainPo,reason:String,code:String,noOncall:Boolean):Boolean{if(notifications.exhaust(chain.id,reason,code)==0)return false;if(!noOncall)chain.incidentId?.let{ensureAlertRepositoryState(incidents.reason(it,reason)==1){"INCIDENT_STATE_CONFLICT:$it:ESCALATION_REASON"}};ensureAlertRepositoryState(audits.exhausted(chain.id,code,chain.ownerTeam)==1);return true}
}
