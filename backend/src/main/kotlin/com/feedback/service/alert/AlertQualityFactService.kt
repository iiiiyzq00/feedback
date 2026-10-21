package com.feedback.service.alert

import com.feedback.security.WebPrincipal
import com.feedback.model.po.alert.IncorrectDedupInsertPo
import com.feedback.repository.alert.AlertQualityFactRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class IncorrectDedupInput(val feedbackSourceId:Long,val relatedSourceId:Long?=null,val errorType:String,val urgency:String="NORMAL",val note:String)
data class RepairIncorrectDedupInput(val note:String)

@Service
class AlertQualityFactService(private val repository:AlertQualityFactRepository) {
    fun maskingF1(at:LocalDateTime=LocalDateTime.now())=latestMasking(at,"end_to_end_f1")
    fun maskingHighRiskRecall(at:LocalDateTime=LocalDateTime.now())=latestMasking(at,"high_risk_recall")
    private fun latestMasking(at:LocalDateTime,column:String):Double{val jsonKey=when(column){"end_to_end_f1"->"endToEndF1";"high_risk_recall"->"highRiskRecall";else->alertAbort("MASKING_METRIC_INVALID")};return repository.latestMetric(at,"$.${jsonKey}")}
    fun openIncorrectDedup(at:LocalDateTime=LocalDateTime.now())=repository.openIncorrectDedup(at)
    fun attachmentFinalCount(start:LocalDateTime,end:LocalDateTime)=repository.attachmentOutcomes(start,end).total.toDouble()
    fun attachmentFailureRate(start:LocalDateTime,end:LocalDateTime)=repository.attachmentOutcomes(start,end).let{if(it.total==0L)0.0 else 100.0*it.failed/it.total}
    @Transactional fun confirmIncorrectDedup(input:IncorrectDedupInput):Map<String,Any>{ensureAlert(input.feedbackSourceId>0&&input.relatedSourceId?.let{it>0}!=false);ensureAlert(input.errorType in setOf("INCORRECT_OVERWRITE","INCORRECT_SKIP","INCORRECT_MERGE"));val urgency=input.urgency.uppercase();ensureAlert(urgency in setOf("URGENT","NORMAL"));ensureAlert(input.note.trim().length in 5..500);val ids=listOfNotNull(input.feedbackSourceId,input.relatedSourceId);ensureAlert(repository.sourcesExist(ids));val actor=actor();val id=repository.insert(IncorrectDedupInsertPo(sourceId=input.feedbackSourceId,relatedId=input.relatedSourceId,errorType=input.errorType,urgency=urgency,note=input.note.trim(),actor=actor));return mapOf("id" to id,"status" to "CONFIRMED","severity" to if(urgency=="URGENT")"P1" else "P2")}
    @Transactional fun repairIncorrectDedup(id:Long,input:RepairIncorrectDedupInput):Map<String,Any>{ensureAlert(input.note.trim().length in 5..500);ensureAlert(repository.repair(id,input.note.trim(),actor())){"错误去重事件不存在或已处置"};return mapOf("id" to id,"status" to "REPAIRED")}
    fun incorrectDedupEvents()=repository.events().map{view->val e=view.row;linkedMapOf<String,Any?>("id" to e.id,"feedbackSourceId" to e.feedbackSourceId,"relatedSourceId" to e.relatedSourceId,"errorType" to e.errorType,"urgency" to e.urgency,"status" to e.status,"confirmationNote" to e.confirmationNote,"confirmedAt" to e.confirmedAt,"repairNote" to e.repairNote,"repairedAt" to e.repairedAt,"confirmedByName" to view.confirmedByName)}
    private fun actor()=(SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal)?.userId?:alertStateAbort("未认证")
}
