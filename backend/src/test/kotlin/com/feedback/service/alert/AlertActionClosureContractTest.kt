package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.alert.AlertQueryIncidentLinkPo
import com.feedback.model.po.alert.AlertQueryInstancePo
import com.feedback.model.po.alert.AlertQueryRulePo
import com.feedback.repository.alert.AlertQueryRepository
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.nio.file.Files
import java.nio.file.Path

class AlertActionClosureContractTest {
    private val repository=mockk<AlertQueryRepository>(relaxed=true)
    private val lifecycle=mockk<AlertIncidentLifecycleService>(relaxed=true)
    private val escalation=mockk<NotificationEscalationService>()
    private val service=AlertQueryService(repository,ObjectMapper(),mockk<DataPermissionService>(),lifecycle,escalation)
    private val alert=AlertQueryInstancePo(id=7,ruleId=3,level="P1",status="FIRING",lifecycleVersion=4)
    private val rule=AlertQueryRulePo(id=3,ruleCode="RULE",ruleName="规则")

    @AfterEach fun clear()=SecurityContextHolder.clearContext()

    @Test fun `all detail actions require caller observed versions`() {
        val controller=Files.readString(Path.of("src/main/kotlin/com/feedback/controller/alert/AlertController.kt"))
        assertTrue(controller.contains("AlertActionRequest(val expectedVersion:Long)"));assertTrue(controller.contains("ResolveAlertRequest(val resolution: String = \"\",val expectedVersion:Long)"));assertTrue(controller.contains("expectedIncidentVersion:Long"));assertTrue(controller.contains("SilenceAlertRequest(val minutes:Int=30,val reason:String=\"\",val expectedVersion:Long)"))
    }

    @Test fun `transfer is role constrained audited and optimistic`() {
        admin();visible();every{repository.eligibleUser(22)} returns true;every{repository.activeIncidentLinks(7)} returns listOf(AlertQueryIncidentLinkPo(1,7,31));every{repository.transferIncident(31,22,"值班交接",9,5)} returns true
        service.transfer(7,22,"值班交接",5,9)
        verify{repository.transferIncident(31,22,"值班交接",9,5)}
        verify{repository.audit(9,"ALERT_TRANSFER",7,match{"\"fromIncidentVersion\":5" in it&&"\"toIncidentVersion\":6" in it})}
    }

    @Test fun `silence locks alert rejects duplicates and keeps p0 protected`() {
        admin();visible();every{repository.lockInstance(7)} returns alert;every{repository.hasActiveSilence(7)} returns false
        service.silence(7,30,"短时维护",4,9)
        verify{repository.insertSilence(7,"短时维护",30,9)}
        verify{repository.audit(9,"ALERT_SILENCE",7,match{"\"alertVersion\":4" in it})}
    }

    @Test fun `acknowledgement audit records whether escalation chain changed`() {
        admin();visible();every{escalation.acknowledgeAlert(7,9)} returns false

        service.acknowledge(7,4,9)

        verify{repository.audit(9,"ALERT_ACK",7,match{"\"escalationChainAcknowledged\":false" in it})}
    }

    private fun visible(){every{repository.instance(7)} returns alert;every{repository.ruleMap(listOf(alert))} returns mapOf(3L to rule)}
    private fun admin(){val p=WebPrincipal(9,"admin","","Admin",setOf("ADMIN"),emptySet());SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(p,"")}
}
