package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.alert.EscalationChainInsertPo
import com.feedback.model.po.alert.EscalationNotificationInsertPo
import com.feedback.model.po.alert.EscalationTargetInsertPo
import com.feedback.model.po.alert.EscalationTimeoutPo
import com.feedback.repository.alert.NotificationEscalationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionOperations
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

internal val PUBLIC_ESCALATION_CHANNELS=listOf("FEISHU_CARD","EMAIL","IN_APP")

@Service
class NotificationEscalationService(private val repository:NotificationEscalationRepository,private val mapper:ObjectMapper,private val transactions:TransactionOperations){
    private val personalChannels=listOf("VOICE_CALL","SMS","FEISHU_DM","IN_APP")
    private val publicChannels=PUBLIC_ESCALATION_CHANNELS

    @Transactional fun start(event:AlertNotificationEvent,ruleId:Long,ownerTeam:String?){
        if(event.severity !in setOf("P0","P1")){repository.insertStandalone(EscalationNotificationInsertPo(ruleId=ruleId,ruleCode=event.ruleCode,alertId=event.instanceId,content=mapper.writeValueAsString(event)));return}
        enqueuePublicBaseline(event,ruleId)
        repository.ensureChain(EscalationChainInsertPo(ruleId,event.ruleCode,event.instanceId,event.incidentId,ownerTeam,event.severity))
        val chain=repository.chainByAlert(event.instanceId)?:return
        if(!repository.hasTargets(chain.id)){
            val targets=ownerTeam?.takeIf(String::isNotBlank)?.let(repository::resolveTargets).orEmpty()
            repository.saveTargets(targets.mapIndexed{i,t->EscalationTargetInsertPo(chain.id,i+1,t.type,t.userId,t.name,mapper.writeValueAsString(personalChannels))})
        }
        enqueueCurrent(chain.displayId,event)
    }

    @Transactional fun reflectSeverityUpgrade(instanceId:Long,targetSeverity:String,ruleId:Long,ruleCode:String,ruleName:String,ownerTeam:String?,incidentId:Long?){
        val chain=repository.chainByAlert(instanceId,true);val chainUpdated=repository.updateSeverity(instanceId,targetSeverity);if(chain!=null)ensureAlertState(chainUpdated){"ESCALATION_CHAIN_STATE_CONFLICT:${chain.displayId}:SEVERITY"};incidentId?.let{repository.updateIncidentSeverity(it,targetSeverity)}
        val content=mapper.writeValueAsString(mapOf("eventType" to "ALERT_ESCALATION","title" to "${ruleName} 级别升级为 $targetSeverity","summary" to "告警级别升级，需按新级别确认与处置","severity" to targetSeverity,"instanceId" to instanceId,"ruleCode" to ruleCode,"status" to "FIRING","escalation" to targetSeverity))
        publicChannels.forEach{channel->if(!repository.escalationExists(instanceId,channel))repository.insertEscalation(EscalationNotificationInsertPo(ruleId=ruleId,ruleCode=ruleCode,alertId=instanceId,channel=channel,content=content))}
        if(chain!=null&&chainUpdated)enqueueCurrent(chain.displayId)
        if(chain==null&&targetSeverity in setOf("P0","P1"))start(AlertNotificationEvent(eventId=UUID.randomUUID().toString(),eventType="ALERT_ESCALATION",title="${ruleName} 级别升级为 $targetSeverity",summary="告警级别升级，需按新级别确认与处置",severity=targetSeverity,status="FIRING",ruleCode=ruleCode,ruleName=ruleName,instanceId=instanceId,incidentId=incidentId,firedAt=OffsetDateTime.now(ZoneId.systemDefault()).toString(),escalation=targetSeverity),ruleId,ownerTeam)
    }

    private fun enqueuePublicBaseline(event:AlertNotificationEvent,ruleId:Long){
        val content=mapper.writeValueAsString(event.copy(eventType="ALERT_FIRED"));repository.insertBaseline(publicChannels.map{EscalationNotificationInsertPo(ruleId=ruleId,ruleCode=event.ruleCode,alertId=event.instanceId,channel=it,content=content)})
        val count=repository.baselineCount(event.instanceId,publicChannels);ensureAlertState(count==publicChannels.size){"PUBLIC_ALERT_BASELINE_INCOMPLETE:${event.instanceId}:$count/${publicChannels.size}"}
    }

    private fun event(chainId:Long):AlertNotificationEvent{
        val chain=alertStateRequired(repository.chain(chainId))
        repository.firstContent(chain.id)?.let{content->runCatching{return mapper.treeToValue(mapper.readTree(content),AlertNotificationEvent::class.java).copy(eventId=UUID.randomUUID().toString(),eventType="ALERT_ESCALATION")}}
        val name=alertStateRequired(repository.ruleName(chain.ruleId));val value=repository.currentValue(chain.alertId)?.toDoubleOrNull()?:0.0
        return AlertNotificationEvent(eventId=UUID.randomUUID().toString(),eventType="ALERT_ESCALATION",title=name,summary="告警尚未确认，正在按责任链升级",severity=chain.severity,status="FIRING",ruleCode=chain.ruleCode,ruleName=name,currentValue=value,instanceId=chain.alertId,incidentId=chain.incidentId,firedAt=OffsetDateTime.now(ZoneId.systemDefault()).toString())
    }

    private fun enqueueCurrent(chainId:Long,eventOverride:AlertNotificationEvent?=null){
        val chain=repository.chain(chainId)?:return;if(chain.status!="RUNNING")return
        val e=eventOverride?:event(chainId)
        val target=repository.target(chain.id,chain.targetOrder)?:return exhaust(chain.displayId,if(repository.targetCount(chain.id)==0)"NO_ELIGIBLE_ONCALL" else "CHAIN_EXHAUSTED")
        val channels=mapper.readTree(target.channelsJson).map{it.asText()};val seq=chain.channelOrder
        if(seq>channels.size){advanceTarget(chain.displayId,"当前责任人的所有渠道均失败或未确认");return}
        val channel=channels[seq-1];val seconds=if(channel=="VOICE_CALL")45 else 120;repository.markTargetProgress(target.id)
        if(target.type=="PUBLIC"&&repository.baselineExists(e.instanceId,channel)){repository.updateDeadline(chain,seconds);return}
        repository.insertCurrent(EscalationNotificationInsertPo(ruleId=chain.ruleId,ruleCode=e.ruleCode,alertId=e.instanceId,chainId=chain.id,targetId=target.displayId,userId=target.userId,deadlineSeconds=seconds,sequence=seq,channel=channel,content=mapper.writeValueAsString(e)))
        repository.updateDeadline(chain,seconds)
    }

    @Transactional fun notificationFailed(notificationId:Long,reason:String){
        val notification=repository.notification(notificationId)?:return;val chainId=notification.chainId?:return;val chain=repository.chainInternal(chainId)?:return;val target=notification.targetId?.let(repository::targetByDisplay)?:return
        if(repository.advanceIfCurrent(chain.id,target.order,notification.channelOrder,reason.take(500)))enqueueCurrent(chain.displayId)
    }

    @Transactional fun acknowledgeByNotification(notificationId:Long,type:String):Boolean{
        val notification=repository.notification(notificationId)?:return false;val chain=notification.chainId?.let(repository::chainInternal)?:return false
        val changed=repository.acknowledgeChain(chain.id,notification.targetUserId)
        if(changed){
            val target=notification.targetId?.let{id->repository.targetByDisplay(id)?.id?:alertAbort("ESCALATION_TARGET_NOT_FOUND:$id")}
            target?.let(repository::acknowledgeTarget);repository.acknowledgeNotification(notificationId,type)
        }
        return changed
    }

    @Transactional fun acknowledgeAlert(alertId:Long,actor:Long):Boolean{
        val chain=repository.chainByAlert(alertId,true)?:return false
        ensureAlertState(repository.acknowledgeAlert(alertId,actor)){"ESCALATION_CHAIN_STATE_CONFLICT:${chain.displayId}:ACKNOWLEDGED"}
        return true
    }
    @Transactional fun cancelAlert(alertId:Long):Boolean{
        val chain=repository.chainByAlert(alertId,true)?:return false
        ensureAlertState(repository.cancelAlert(alertId)){"ESCALATION_CHAIN_STATE_CONFLICT:${chain.displayId}:CANCELLED"}
        return true
    }
    fun scanTimeouts(){repository.timedOut().forEach{row->transactions.execute{advanceTimedOut(row,"确认超时");Unit}}}
    private fun advanceTimedOut(row:EscalationTimeoutPo,reason:String){if(repository.advanceTimedOut(row,reason.take(500)))enqueueCurrent(row.displayId)}
    private fun advanceTarget(chainId:Long,reason:String){val chain=repository.chain(chainId)?:return;if(repository.advanceTarget(chain,reason))enqueueCurrent(chain.displayId)}
    private fun exhaust(chainId:Long,reasonCode:String){
        val chain=repository.chain(chainId)?:return;val noOncall=reasonCode=="NO_ELIGIBLE_ONCALL";val reason=if(noOncall)"主责团队未配置当前有效值班人或团队负责人，告警尚未通知到具体责任人" else "已配置责任人的通知升级链均未确认，等待人工处置"
        repository.exhaust(chain,reason,reasonCode,noOncall)
    }
}
