package com.feedback.service.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.alert.EscalationChainPo
import com.feedback.model.po.alert.EscalationNotificationInsertPo
import com.feedback.repository.alert.NotificationEscalationRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations

class NotificationEscalationSeverityUpgradeTest {
    private val repository=mockk<NotificationEscalationRepository>()
    private val mapper=jacksonObjectMapper().findAndRegisterModules()
    private val service=NotificationEscalationService(repository,mapper,immediateTransactions())

    @Test fun `severity upgrade updates running chain severity and rebroadcasts public channels`() {
        val chain=EscalationChainPo(5,5,9,3,2,"VERSION_REGRESSION","P0",status="ACKNOWLEDGED")
        every{repository.chainByAlert(9,true)} returns chain;every{repository.updateSeverity(9,"P0")} returns true;every{repository.updateIncidentSeverity(3,"P0")} just Runs
        every{repository.escalationExists(9,any())} returns false;val rows=mutableListOf<EscalationNotificationInsertPo>();every{repository.insertEscalation(capture(rows))} just Runs;every{repository.chain(5)} returns chain
        every{repository.firstContent(5)} returns mapper.writeValueAsString(AlertNotificationEvent(eventId="existing",eventType="ALERT_FIRED",title="版本回归",summary="版本回归",severity="P1",status="FIRING",ruleCode="VERSION_REGRESSION",ruleName="版本回归",instanceId=9,firedAt="2026-09-02T00:00:00+08:00"))
        service.reflectSeverityUpgrade(9,"P0",2,"VERSION_REGRESSION","版本回归","产品+研发",3)
        assertEquals(PUBLIC_ESCALATION_CHANNELS.toSet(),rows.map{it.channel}.toSet());verify{repository.updateIncidentSeverity(3,"P0")}
    }

    @Test fun `severity upgrade to P0 starts a fresh escalation chain when none exists`() {
        every{repository.chainByAlert(9,true)} returns null;every{repository.updateSeverity(9,"P0")} returns false;every{repository.escalationExists(9,any())} returns false
        val escalations=mutableListOf<EscalationNotificationInsertPo>();every{repository.insertEscalation(capture(escalations))} just Runs;every{repository.insertBaseline(any())} just Runs;every{repository.baselineCount(9,any())} returns 3;every{repository.ensureChain(any())} returns 1
        val chain=EscalationChainPo(7,7,9,null,2,"RULE_900","P0","数据平台",status="ACKNOWLEDGED");every{repository.chainByAlert(9,false)} returns chain;every{repository.hasTargets(7)} returns true;every{repository.chain(7)} returns chain
        service.reflectSeverityUpgrade(9,"P0",2,"RULE_900","P2规则升级","数据平台",null)
        assertEquals(PUBLIC_ESCALATION_CHANNELS.toSet(),escalations.map{it.channel}.toSet());verify{repository.ensureChain(any())}
    }

    @Test fun `running chain severity conflict stops notification rebroadcast`() {
        val chain=EscalationChainPo(5,5,9,3,2,"VERSION_REGRESSION","P1",status="RUNNING")
        every{repository.chainByAlert(9,true)} returns chain;every{repository.updateSeverity(9,"P0")} returns false

        org.junit.jupiter.api.assertThrows<com.feedback.common.exception.ServiceException>{service.reflectSeverityUpgrade(9,"P0",2,"VERSION_REGRESSION","版本回归","产品+研发",3)}

        verify(exactly=0){repository.updateIncidentSeverity(any(),any());repository.insertEscalation(any())}
    }

    private fun immediateTransactions():TransactionOperations=object:TransactionOperations{override fun <T:Any?> execute(action:TransactionCallback<T>):T?=action.doInTransaction(mockk<TransactionStatus>())}
}
