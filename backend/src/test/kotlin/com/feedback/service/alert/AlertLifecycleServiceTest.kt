package com.feedback.service.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.alert.AlertCatalogPolicyPo
import com.feedback.model.po.alert.AlertEscalationCandidatePo
import com.feedback.model.po.alert.AlertLifecycleRulePo
import com.feedback.model.po.alert.AlertSuppressionPo
import com.feedback.repository.alert.AlertLifecycleRepository
import com.feedback.security.WebPrincipal
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.LocalDateTime
import java.time.OffsetDateTime

class AlertLifecycleServiceTest {
    private val repository=mockk<AlertLifecycleRepository>(relaxed=true)
    private val dispatcher=mockk<NotificationDispatcher>(relaxed=true)
    private val escalation=mockk<NotificationEscalationService>(relaxed=true)
    private val service=AlertLifecycleService(repository,jacksonObjectMapper(),dispatcher,escalation)

    @AfterEach fun clearContext()=SecurityContextHolder.clearContext()

    @Test fun `global silence does not suppress a P0 non silenceable rule`() {
        every { repository.activeSuppressions() } returns listOf(AlertSuppressionPo(scope="""{"all":true}"""))
        every { repository.rule(7) } returns AlertLifecycleRulePo(id=7,ruleCode="SECURITY_P0")
        every { repository.policies(listOf("SECURITY_P0")) } returns listOf(AlertCatalogPolicyPo("SECURITY_P0","P0",true))
        assertFalse(service.isSuppressed(7))
    }

    @Test fun `rule specific silence still matches its rule`() {
        every { repository.activeSuppressions() } returns listOf(AlertSuppressionPo(scope="""{"ruleId":7}"""))
        assertTrue(service.isSuppressed(7))
        verify(exactly=0){repository.rule(any())}
    }

    @Test fun `create silence validates active rule and persists authenticated scope`() {
        authenticate(19)
        every { repository.rule(7) } returns AlertLifecycleRulePo(id=7,ruleCode="QUALITY",versionStatus="ACTIVE")
        every { repository.policies(listOf("QUALITY")) } returns emptyList()
        every { repository.overlaps(any(),any(),7) } returns false
        every { repository.createSuppression(any()) } returns 88
        val result=service.createSilence(SilenceInput("MANUAL",7,OffsetDateTime.now().plusMinutes(1),OffsetDateTime.now().plusHours(1),"维护窗口"))
        assertEquals(88L,result["id"])
        verify { repository.createSuppression(match { it.actor==19L&&it.type=="MANUAL"&&"\"ruleId\":7" in it.scopeJson }) }
    }

    @Test fun `escalation pages primary alerts and skips existing target marker`() {
        val due=LocalDateTime.now().minusHours(2)
        every { repository.pageOpen(0,500) } returns listOf(
            AlertEscalationCandidatePo(1,11,"RULE_A","P2",due),
            AlertEscalationCandidatePo(2,12,"RULE_B","P2",due),
        )
        every { repository.activeRules(listOf("RULE_A","RULE_B")) } returns listOf(
            AlertLifecycleRulePo(21,"RULE_A","规则A","TEAM_A","""{"escalation":"30M_TO_P1"}""","ACTIVE"),
            AlertLifecycleRulePo(22,"RULE_B","规则B","TEAM_B","""{"escalation":"30M_TO_P1"}""","ACTIVE"),
        )
        every { repository.escalationMarkers(listOf(1L,2L)) } returns listOf(com.feedback.model.po.alert.AlertEscalationMarkerPo(2,"""{"escalation":"P1"}"""))
        every { repository.latestIncidents(listOf(1L)) } returns mapOf(1L to 31L)
        assertEquals(1,service.escalate())
        verify { repository.updateSeverity(listOf(1L),"P1") }
        verify { escalation.reflectSeverityUpgrade(1,"P1",11,"RULE_A","规则A","TEAM_A",31) }
        verify { repository.insertEscalationNotifications(match { it.size==1&&it.single().alertInstanceId==1L }) }
    }

    private fun authenticate(id:Long){
        val principal=WebPrincipal(id,"operator","","Operator",setOf("OPERATOR"),emptySet())
        SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(principal,"")
    }
}
