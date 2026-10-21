package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository

data class AlertRuleTestRunView(val id:Long,val ruleId:Long?,val ruleCode:String?,val ruleName:String?,val mode:String?,val status:String?,val matched:Boolean?,val sampleSize:Int?,val externalNotificationsSent:Int?,val createdAt:java.time.LocalDateTime?,val completedAt:java.time.LocalDateTime?,val createdByName:String?)

@Repository
class AlertRuleRepository(private val configs:AlertRuleConfigMapper,private val rules:AlertRuleManagementMapper,private val instances:AlertRuleInstanceMapper,private val tasks:AlertRuleTestTaskMapper,private val users:AlertRuleTestUserMapper){
 fun expectedCount()=configs.expectedCount()?:34
 fun catalog()=configs.catalog()
 fun list():List<Pair<AlertRuleListPo,java.time.LocalDateTime?>>{val rows=rules.list();if(rows.isEmpty())return emptyList();val last=instances.lastTriggered(rows.map{it.id}).associate{it.ruleId to it.lastTriggeredAt};return rows.map{it to last[it.id]}}
 fun nextPriority()=rules.nextPriority()
 fun insert(row:AlertRuleInsertPo):Long{ensureAlertRepositoryState(rules.insert(row)==1);return alertRepositoryStateRequired(row.id)}
 fun lockActive(id:Long)=rules.lockActive(id)
 fun retire(id:Long){ensureAlertRepositoryState(rules.retire(id)==1)}
 fun toggle(id:Long,enabled:Boolean)=rules.toggle(id,enabled)==1
 fun reorder(ids:List<Long>){val active=rules.lockActiveIds(ids).toSet();ensureAlertRepositoryInput(active.size==ids.size){"包含无效规则"};val rows=ids.mapIndexed{i,id->AlertRulePriorityPo(id,(i+1)*10)};rules.reorder(rows)}
 fun ruleCode(id:Long)=rules.ruleCode(id)
 fun insertTest(row:AlertRuleTestInsertPo):Long{ensureAlertRepositoryState(tasks.insert(row)==1);return alertRepositoryStateRequired(row.id)}

 /** 按任务主键向后扫描，并在代码中恢复原用户 INNER JOIN、规则 LEFT JOIN 与 LIMIT 语义。 */
 fun testRuns(limit:Int):List<AlertRuleTestRunView>{
  val result=ArrayList<AlertRuleTestRunView>(limit);var before:Long?=null
  while(result.size<limit){
   val page=tasks.page(before,500);if(page.isEmpty())break
   val userMap=users.users(page.map{it.userId}.distinct()).associateBy{it.id}
   val ruleIds=page.mapNotNull{it.ruleId}.distinct();val ruleMap=if(ruleIds.isEmpty())emptyMap() else rules.testRules(ruleIds).associateBy{it.id}
   page.forEach{row->val user=userMap[row.userId]?:return@forEach;val rule=row.ruleId?.let(ruleMap::get);result+=AlertRuleTestRunView(row.id,row.ruleId,rule?.ruleCode,rule?.ruleName,row.mode,row.status,row.matched,row.sampleSize,row.externalNotificationsSent,row.createdAt,row.completedAt,user.displayName)}
   before=page.last().rawId;if(page.size<500)break
  }
  return result.take(limit)
 }
}
