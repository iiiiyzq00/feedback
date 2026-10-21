package com.feedback.service.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.alert.NotificationEnqueuePo
import com.feedback.model.po.alert.NotificationOutboxPo
import com.feedback.repository.alert.NotificationDispatchRepository
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NotificationDispatcherTest {
    private val repository=mockk<NotificationDispatchRepository>()
    private val outbox=mockk<NotificationOutbox>()
    private val emailSender=mockk<EmailSender>()
    private val metrics=mockk<NotificationMetrics>(relaxed=true)
    private val mapper=jacksonObjectMapper().findAndRegisterModules()
    private val dispatcher=NotificationDispatcher(repository,mapper,outbox,emailSender,metrics,"","")

    @Test fun `dispatch persists a deduplicated pending outbox record per supported channel`(){
        every{repository.enqueue(any())} just Runs
        dispatcher.dispatch(event(),7,listOf("inapp","IN_APP","feishu","SMS"))
        verify(exactly=4){repository.enqueue(any())}
    }

    @Test fun `dispatch persists all eight governed channels exactly once`() {
        val captured=mutableListOf<NotificationEnqueuePo>();every{repository.enqueue(capture(captured))} just Runs
        dispatcher.dispatch(event("P0"),7,listOf("FEISHU_CARD","FEISHU_ALL","FEISHU_DM","FEISHU_TASK","SMS","VOICE_CALL","EMAIL","IN_APP","voice_call"),listOf("FEISHU:ou_test_operator"))
        assertEquals(NotificationDispatcher.CHANNELS,captured.map{it.channel}.toSet());assertEquals(8,captured.size)
        assertTrue(captured.none{it.recipientsJson.contains("@")||it.recipientsJson.matches(Regex(".*\\+?[0-9]{6,20}.*"))})
    }

    @Test fun `in-app delivery completes claimed record without network`() {
        val row=row(9,"IN_APP");every{outbox.claimOne()} returns row;every{outbox.complete(any(),any(),any())} just Runs
        assertTrue(dispatcher.deliverOne());verify(exactly=1){outbox.complete(row,match{it.delivered},any())}
    }

    @Test fun `missing webhook records delivery failure for retry`() {
        val row=row(10,"FEISHU","{\"title\":\"x\",\"severity\":\"P1\",\"instanceId\":1}");every{outbox.claimOne()} returns row;every{outbox.complete(any(),any(),any())} just Runs
        assertTrue(dispatcher.deliverOne());verify{outbox.complete(row,match{!it.delivered&&it.errorType=="CONFIGURATION"},any())}
    }

    @Test fun `voice call without provider configuration fails closed`() {
        val row=row(12,"VOICE_CALL");every{outbox.claimOne()} returns row;every{outbox.complete(any(),any(),any())} just Runs
        assertTrue(dispatcher.deliverOne());verify{outbox.complete(row,match{!it.delivered&&it.errorType=="CONFIGURATION"},any())}
    }

    @Test fun `feishu api channels without token or recipients fail closed`() {
        for(channel in listOf("FEISHU_DM","FEISHU_TASK")){clearMocks(outbox);val row=row(13,channel);every{outbox.claimOne()} returns row;every{outbox.complete(any(),any(),any())} just Runs
            assertTrue(dispatcher.deliverOne());verify{outbox.complete(row,match{!it.delivered&&it.errorType=="CONFIGURATION"},any())}}
    }

    @Test fun `dispatch stores one immutable event snapshot for every channel`() {
        val captured=mutableListOf<NotificationEnqueuePo>();every{repository.enqueue(capture(captured))} just Runs
        dispatcher.dispatch(event(),7,listOf("IN_APP","FEISHU","EMAIL"))
        assertEquals(3,captured.size);assertTrue(captured.all{mapper.readTree(it.content)["eventId"].asText()=="event-1"&&it.policyVersion=="v2"})
    }

    @Test fun `p1 optional personal channels are not generated unless explicitly configured`() {
        val captured=mutableListOf<NotificationEnqueuePo>();every{repository.enqueue(capture(captured))} just Runs
        dispatcher.dispatch(event(),7,listOf("IN_APP"))
        assertEquals(setOf("FEISHU_CARD","EMAIL","IN_APP"),captured.map{it.channel}.toSet());assertTrue(captured.none{it.channel in setOf("FEISHU_DM","FEISHU_TASK","SMS")})
    }

    @Test fun `quick rca delivery is blocked until its source alert is delivered on the same channel`() {
        val rca=event().copy(eventId="quick-1",eventType="QUICK_RCA_READY",rcaId=8);val row=row(18,"FEISHU_CARD",mapper.writeValueAsString(rca))
        every{outbox.claimOne()} returns row;every{repository.sourceDelivered(11,"FEISHU_CARD")} returns false;every{outbox.complete(any(),any(),any())} just Runs
        assertTrue(dispatcher.deliverOne());verify{outbox.complete(row,match{!it.delivered&&it.errorType=="SOURCE_ALERT_NOT_DELIVERED"},0)};verify(exactly=0){emailSender.send(any())}
    }

    private fun row(id:Long,channel:String,content:String="{}")=NotificationOutboxPo(id,channel,"[]",content,0,5)
    private fun event(severity:String="P1")=AlertNotificationEvent(eventId="event-1",eventType="ALERT_FIRED",title="AI辅导错误率告警",summary="错误率 18.6%，超过阈值 10%",severity=severity,status="FIRING",ruleCode="RULE_X",ruleName="错误率规则",metric="ERROR_RATE_HIGH",currentValue=18.6,threshold=10.0,window="15m",recoveryThreshold=8.0,instanceId=11,incidentId=5,firedAt="2026-07-28T16:00:00+08:00",detailUrl="https://example.test/alerts/11")
}
