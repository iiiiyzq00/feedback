package com.feedback.service.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.alert.AlertRuleInsertPo
import com.feedback.model.po.alert.AlertRuleLockedPo
import com.feedback.repository.alert.AlertRuleRepository
import com.feedback.security.WebPrincipal
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class AlertRuleServiceTest {
    private val repository=mockk<AlertRuleRepository>()
    private val notificationPolicy=mockk<NotificationPolicy>()
    private val governance=mockk<AlertProductionGovernanceService>()
    private val service=AlertRuleService(repository,jacksonObjectMapper(),notificationPolicy,governance)
    private val input=AlertRuleInput("测试规则","P2","FEEDBACK_SPIKE",10.0,"5m")

    @AfterEach fun clear()=SecurityContextHolder.clearContext()

    @Test fun `create preserves complete rule payload and starts disabled`() {
        every { notificationPolicy.resolve("P2",listOf("inapp")) } returns (listOf("IN_APP") to SeverityNotificationPolicy(emptySet(),setOf("IN_APP"),"STANDARD"))
        every { repository.nextPriority() } returns 40
        val inserted=slot<AlertRuleInsertPo>();every { repository.insert(capture(inserted)) } returns 91

        assertEquals(91,service.create(input))
        assertEquals("测试规则",inserted.captured.name);assertEquals("P2",inserted.captured.level)
        assertEquals(40,inserted.captured.priority);assertEquals(false,inserted.captured.enabled)
    }

    @Test fun `update retires locked active version and carries enabled state`() {
        every { notificationPolicy.resolve("P2",listOf("inapp")) } returns (listOf("IN_APP") to SeverityNotificationPolicy(emptySet(),setOf("IN_APP"),"STANDARD"))
        every { repository.lockActive(7) } returns AlertRuleLockedPo("RULE_A","v4",true,30)
        every { repository.retire(7) } returns Unit
        val inserted=slot<AlertRuleInsertPo>();every { repository.insert(capture(inserted)) } returns 8

        assertEquals(8,service.update(7,input));verify(exactly=1){repository.retire(7)}
        assertEquals("v5",inserted.captured.version);assertEquals(true,inserted.captured.enabled)
    }

    @Test fun `enabling fails closed before mutation when production governance is not ready`() {
        every { governance.activationGaps(5) } returns mapOf("readyToPublish" to false)
        assertThrows(com.feedback.common.exception.ClientException::class.java){service.toggle(5,true)}
        verify(exactly=0){repository.toggle(any(),any())}
    }

    @Test fun `simulation remains authenticated and never sends external notification`() {
        val principal=WebPrincipal(3,"operator","hash","Operator",setOf("OPERATOR"),emptySet())
        SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(principal,null,principal.authorities)
        every { notificationPolicy.resolve("P2",listOf("inapp")) } returns (listOf("IN_APP") to SeverityNotificationPolicy(emptySet(),setOf("IN_APP"),"STANDARD"))
        every { repository.insertTest(any()) } returns 12

        val result=service.test(TestRuleInput(input,mapOf("FEEDBACK_SPIKE" to 11.0)))
        assertEquals(true,result["matched"]);assertEquals(0,result["externalNotificationsSent"]);assertEquals(12L,result["runId"])
    }
}
