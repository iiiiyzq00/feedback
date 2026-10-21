package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository

data class IsolatedDrillView(val id:Long,val testRunId:Long,val ruleId:Long,val ruleCode:String?,val title:String?,val severity:String?,val status:String,val notificationChannel:String?,val productionImpact:Boolean,val createdAt:java.time.LocalDateTime?,val createdByName:String?)

@Repository class AlertRuleIsolatedRepository(private val rules:IsolatedRuleMapper,private val tasks:IsolatedTaskMapper,private val users:IsolatedUserMapper,private val audits:IsolatedAuditMapper){
 fun rule(id:Long)=alertRepositoryRequired(rules.find(id)){"规则运行版本不存在"}
 fun insertTest(row:IsolatedTestInsertPo):Long{ensureAlertRepositoryState(tasks.insertTest(row)==1);return alertRepositoryStateRequired(row.id)}
 fun updateResult(id:Long,result:String){ensureAlertRepositoryState(tasks.updateResult(id,result)==1)}
 fun insertDrill(row:IsolatedDrillInsertPo):Long{ensureAlertRepositoryState(tasks.insertDrill(row)==1);return alertRepositoryStateRequired(row.id)}
 fun drills(limit:Int):List<IsolatedDrillView>{val result=mutableListOf<IsolatedDrillView>();var before:Long?=null;while(result.size<limit){val page=tasks.drillPage(before,500);if(page.isEmpty())break;val parentIds=page.map{it.parentId}.distinct();val parentMap=tasks.parents(parentIds).associateBy{it.id};val userMap=users.users(page.map{it.userId}.distinct()).associateBy{it.id};page.forEach{d->val parent=parentMap[d.parentId]?:return@forEach;val user=userMap[d.userId]?:return@forEach;result+=IsolatedDrillView(d.id,parent.displayId,d.ruleId,d.ruleCode,d.title,d.severity,d.status,d.notificationChannel,d.productionImpact,d.createdAt,user.displayName)};before=page.last().rawId;if(page.size<500)break};return result.take(limit)}
 fun lockDrill(id:Long)=tasks.lockDrill(id)?:alertRepositoryMissing("ALERT_DRILL_NOT_FOUND")
 fun transition(row:IsolatedLockedDrillPo,target:String,reason:String,actor:Long,displayId:Long){ensureAlertRepositoryState(tasks.transition(row.id,target,reason,actor)==1);ensureAlertRepositoryState(audits.insert(IsolatedAuditInsertPo(actor,"ALERT_DRILL_$target",displayId.toString(),row.status,target,reason))==1)}
}
