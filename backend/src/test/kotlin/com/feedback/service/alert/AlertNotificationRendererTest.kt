package com.feedback.service.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AlertNotificationRendererTest {
    private val mapper=jacksonObjectMapper().findAndRegisterModules()
    private val renderer=AlertNotificationRenderer(mapper)
    private val event=AlertNotificationEvent(eventId="evt-123",eventType="ALERT_FIRED",title="AI辅导 <错误率> 告警",summary="错误率 18.6%，超过阈值 10%",severity="P1",status="FIRING",ruleCode="RULE_ERROR",ruleName="错误率规则",metric="ERROR_RATE_HIGH",currentValue=18.6,threshold=10.0,window="15m",recoveryThreshold=8.0,instanceId=123,incidentId=45,firedAt="2026-07-28T16:30:00+08:00",detailUrl="https://feedback.example.com/alerts/123")

    @Test fun `email contains complete facts text html and escaped user visible values`() {
        val mail=renderer.email(event,listOf("ops@example.com"))
        assertEquals("evt-123",mail.eventId);assertEquals(listOf("ops@example.com"),mail.recipients)
        assertTrue(mail.subject.contains("[P1]"));assertTrue(mail.text.contains("当前值：18.6"));assertTrue(mail.text.contains("Incident：45"))
        assertTrue(mail.html.contains("AI辅导 &lt;错误率&gt; 告警"));assertFalse(mail.html.contains("<错误率>"))
    }

    @Test fun `feishu and email render from the same immutable event`() {
        val card=mapper.readTree(renderer.feishu(event));val mail=renderer.email(event,listOf("ops@example.com"))
        assertEquals("interactive",card["msg_type"].asText());assertEquals(event.eventId,mail.eventId)
        assertEquals(event.ruleCode,mail.metadata["ruleCode"])
        assertTrue(card.toString().contains("反馈错误率"));assertTrue(mail.text.contains("反馈错误率"))
    }

    @Test fun `risk brief card does not pretend to be alert instance`() {
        val risk=event.copy(eventType="RISK_BRIEF_PUBLISHED",title="风险与舆情简报 2026-08-02",summary="本期风险等级：低（评分 0）",status="PUBLISHED",ruleCode="RISK_BRIEF",ruleName="风险与舆情简报",instanceId=0,reportId=41,riskLevel="LOW",riskScore=0.0,keyFindings=listOf("本周期未发现需要立即处置的重点风险"))
        val card=renderer.feishu(risk)
        assertTrue(card.contains("风险等级"));assertTrue(card.contains("报告编号"));assertTrue(card.contains("重点关注"))
        assertFalse(card.contains("告警实例"));assertFalse(card.contains("UNKNOWN"));assertFalse(card.contains("LEGACY"))
    }

    @Test fun `quick rca card exposes evidence and next action`() {
        val rca=event.copy(eventType="QUICK_RCA_READY",title="快速 RCA 已生成",summary="已完成第一轮事实分析",status="PENDING_REVIEW",rcaId=88,rootCause="错误集中在 SDK 5.2.1",confidence=.65,impactSummary="关联 1 个告警、36 条有效反馈",evidenceLimitations=listOf("缺少服务端日志"),suggestedActions=listOf("核对同窗口错误日志"))
        val card=renderer.feishu(rca)
        assertTrue(card.contains("根因线索"));assertTrue(card.contains("影响范围"));assertTrue(card.contains("65%"));assertTrue(card.contains("证据限制"));assertTrue(card.contains("下一步"))
        assertFalse(card.contains("告警实例"));assertFalse(card.contains("规则："))
    }

    @Test fun `feishu and email can render the source alert before its quick rca`() {
        val rca=event.copy(eventId="quick-rca-88",eventType="QUICK_RCA_READY",title="快速 RCA 已生成",summary="第一轮事实分析完成",status="PENDING_REVIEW",rcaId=88,rootCause="待复核根因线索",confidence=.6)
        val alertCard=renderer.feishu(event);val rcaCard=renderer.feishu(rca)
        val alertMail=renderer.email(event,listOf("ops@example.com"));val rcaMail=renderer.email(rca,listOf("ops@example.com"))
        assertTrue(alertCard.contains("告警实例"));assertTrue(alertCard.contains("触发原因"))
        assertTrue(rcaCard.contains("RCA 编号"));assertTrue(rcaCard.contains("当前置信度"))
        assertTrue(alertMail.text.contains("告警实例：123"));assertTrue(rcaMail.text.contains("RCA 编号：88"))
        assertEquals("evt-123",alertMail.eventId);assertEquals("quick-rca-88",rcaMail.eventId)
    }
}
