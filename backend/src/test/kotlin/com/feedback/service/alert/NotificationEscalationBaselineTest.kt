package com.feedback.service.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.alert.EscalationChainPo
import com.feedback.model.po.alert.EscalationNotificationInsertPo
import com.feedback.repository.alert.NotificationEscalationRepository
import io.mockk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations

class NotificationEscalationBaselineTest {
    private val repository=mockk<NotificationEscalationRepository>()
    private val service=NotificationEscalationService(repository,jacksonObjectMapper().findAndRegisterModules(),immediateTransactions())

    @Test fun `p1 source alert is queued to every public channel before personal escalation`() {
        val rows=mutableListOf<List<EscalationNotificationInsertPo>>();every{repository.insertBaseline(capture(rows))} just Runs;every{repository.baselineCount(9,any())} returns 3
        every{repository.ensureChain(any())} returns 1;every{repository.chainByAlert(9,false)} returns chain(9,"ACKNOWLEDGED");every{repository.hasTargets(7)} returns true;every{repository.chain(7)} returns chain(9,"ACKNOWLEDGED")
        service.start(event(9),2,null)
        assert(rows.single().map{it.channel}.toSet()==PUBLIC_ESCALATION_CHANNELS.toSet())
    }

    @Test fun `p1 creation fails closed when any public baseline channel is missing`() {
        every{repository.insertBaseline(any())} just Runs;every{repository.baselineCount(10,any())} returns 2
        assertThrows<com.feedback.common.exception.ServiceException>{service.start(event(10),2,null)}
    }

    @Test fun `empty personal chain records configuration risk without changing incident severity`() {
        every{repository.insertBaseline(any())} just Runs;every{repository.baselineCount(12,any())} returns 3;every{repository.ensureChain(any())} returns 1
        val chain=chain(12,"RUNNING");every{repository.chainByAlert(12,false)} returns chain;every{repository.hasTargets(7)} returns false;every{repository.resolveTargets("隐私平台")} returns emptyList();every{repository.saveTargets(emptyList())} just Runs
        every{repository.chain(7)} returns chain;every{repository.target(7,1)} returns null;every{repository.targetCount(7)} returns 0;every{repository.exhaust(chain,match{it.contains("未配置")},"NO_ELIGIBLE_ONCALL",true)} returns true
        service.start(event(12),2,"隐私平台")
        verify(exactly=1){repository.exhaust(chain,any(),"NO_ELIGIBLE_ONCALL",true)};verify(exactly=0){repository.updateIncidentSeverity(any(),"P0")}
    }

    private fun chain(alert:Long,status:String)=EscalationChainPo(7,7,alert,6,2,"MASKING_FAILURE","P1","隐私平台",1,1,status)
    private fun event(id:Long)=AlertNotificationEvent(eventId="alert-$id",eventType="ALERT_FIRED",title="告警",summary="超过阈值",severity="P1",status="FIRING",ruleCode="MASKING_FAILURE",ruleName="脱敏失败率过高",instanceId=id,incidentId=6,firedAt="2026-08-04T11:00:00+08:00")
    private fun immediateTransactions():TransactionOperations=object:TransactionOperations{override fun <T:Any?> execute(action:TransactionCallback<T>):T?=action.doInTransaction(mockk<TransactionStatus>())}
}
