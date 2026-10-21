package com.feedback.service.alert

import com.feedback.security.WebPrincipal
import com.feedback.model.po.alert.AlertAcceptanceInsertPo
import com.feedback.repository.alert.AlertRuleAcceptanceRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

data class AlertAcceptanceInput(val ruleId:Long,val replayRunId:Long,val drillId:Long,val truePositive:Long,val falsePositive:Long,val falseNegative:Long,val trueNegative:Long,val routingOutcome:String,val evidenceSha256:String)

@Service
class AlertRuleAcceptanceService(private val repository:AlertRuleAcceptanceRepository) {
    @Transactional fun validate(input:AlertAcceptanceInput):Map<String,Any>{ensureAlert(listOf(input.truePositive,input.falsePositive,input.falseNegative,input.trueNegative).all{it>=0}&&input.truePositive+input.falsePositive+input.falseNegative+input.trueNegative>0);ensureAlert(input.routingOutcome in setOf("PASSED","FAILED")&&input.evidenceSha256.matches(Regex("^[a-fA-F0-9]{64}$")));val replay=repository.replay(input.replayRunId)?:alertAbort("ALERT_ACCEPTANCE_REPLAY_INVALID");ensureAlert(replay.ruleId==input.ruleId&&replay.testMode=="REPLAY"&&replay.runStatus=="COMPLETED"&&replay.externalNotificationsSent==0){"ALERT_ACCEPTANCE_REPLAY_INVALID"};val drill=repository.drill(input.drillId)?:alertAbort("ALERT_ACCEPTANCE_DRILL_INVALID");ensureAlert(drill.ruleId==input.ruleId&&drill.productionImpact!=1&&drill.notificationChannel=="IN_APP"){"ALERT_ACCEPTANCE_DRILL_INVALID"};val precision=ratio(input.truePositive,input.truePositive+input.falsePositive);val recall=ratio(input.truePositive,input.truePositive+input.falseNegative);val actor=actor();val id=repository.insert(AlertAcceptanceInsertPo(ruleId=input.ruleId,parentTaskId=replay.id,drillId=input.drillId,replayRunId=input.replayRunId,truePositive=input.truePositive,falsePositive=input.falsePositive,falseNegative=input.falseNegative,trueNegative=input.trueNegative,precision=precision,recall=recall,routingOutcome=input.routingOutcome,evidenceSha256=input.evidenceSha256.lowercase(),actor=actor));return mapOf("id" to id,"status" to "VALIDATED","precision" to precision,"recall" to recall,"routingOutcome" to input.routingOutcome)}
    @Transactional fun approve(id:Long):Map<String,Any>{val actor=actor();ensureAlert(repository.approve(id,actor)){"ALERT_ACCEPTANCE_INDEPENDENT_APPROVAL_REQUIRED"};return mapOf("id" to id,"status" to "APPROVED")}
    fun list(ruleId:Long?)=repository.list(ruleId).map{view->val a=view.row;linkedMapOf<String,Any?>("id" to a.id,"ruleId" to a.ruleId,"ruleCode" to view.ruleCode,"replayRunId" to a.replayRunId,"drillId" to a.drillId,"truePositive" to a.truePositive,"falsePositive" to a.falsePositive,"falseNegative" to a.falseNegative,"trueNegative" to a.trueNegative,"precisionRate" to a.precisionRate,"recallRate" to a.recallRate,"routingOutcome" to a.routingOutcome,"evidenceSha256" to a.evidenceSha256,"status" to a.status,"assessedBy" to a.assessedBy,"assessedByName" to view.assessedByName,"approvedBy" to a.approvedBy,"approvedByName" to view.approvedByName,"assessedAt" to a.assessedAt,"approvedAt" to a.approvedAt)}
    private fun ratio(a:Long,b:Long)=if(b==0L)BigDecimal.ZERO else BigDecimal(a).divide(BigDecimal(b),6,RoundingMode.HALF_UP)
    private fun actor()=(SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal)?.userId?:alertStateAbort("未认证")
}
