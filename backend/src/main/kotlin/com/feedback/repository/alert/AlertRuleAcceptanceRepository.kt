package com.feedback.repository.alert
import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository

data class AlertAcceptanceView(val row:AlertAcceptancePo,val ruleCode:String?,val assessedByName:String?,val approvedByName:String?)
@Repository class AlertRuleAcceptanceRepository(private val tasks:AlertAcceptanceTaskMapper,
 private val rules:AlertAcceptanceRuleMapper,private val users:AlertAcceptanceUserMapper){
 fun replay(id:Long)=tasks.findReplay(id)
 fun drill(id:Long)=tasks.findDrill(id)
 fun insert(row:AlertAcceptanceInsertPo):Long{ensureAlertRepositoryState(tasks.insert(row)==1&&row.id>0);return row.id}
 fun approve(id:Long,actor:Long)=tasks.approve(id,actor)==1
 fun list(ruleId:Long?):List<AlertAcceptanceView>{
  val rows=tasks.findAcceptances(ruleId)
  val ruleIds=rows.map{it.ruleId}.distinct();val userIds=rows.flatMap{listOfNotNull(it.assessedBy,it.approvedBy)}.distinct()
  val codes=if(ruleIds.isEmpty())emptyMap() else rules.findCodes(ruleIds).associate{it.id to it.ruleCode}
  val names=if(userIds.isEmpty())emptyMap() else users.findNames(userIds).associate{it.id to it.displayName}
  return rows.mapNotNull{row->if(row.ruleId !in codes)null else AlertAcceptanceView(row,codes[row.ruleId],
   row.assessedBy?.let(names::get),row.approvedBy?.let(names::get))}
 }
}
