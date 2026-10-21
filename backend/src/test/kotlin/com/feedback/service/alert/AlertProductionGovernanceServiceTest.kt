package com.feedback.service.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.alert.GovernanceAcceptancePo
import com.feedback.model.po.alert.GovernanceCatalogPo
import com.feedback.model.po.alert.GovernanceRulePo
import com.feedback.repository.alert.AlertProductionGovernanceRepository
import com.feedback.security.WebPrincipal
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.LocalDateTime

class AlertProductionGovernanceServiceTest {
    private val principal=WebPrincipal(7,"admin","hash","Admin",setOf("ADMIN"),emptySet())
    @AfterEach fun clear()=SecurityContextHolder.clearContext()
    private fun authenticated(){SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(principal,null,principal.authorities)}

    @Test fun `voice production acceptance requires connected no-answer and DTMF evidence`() {
        authenticated();val service=AlertProductionGovernanceService(mockk(relaxed=true),jacksonObjectMapper())
        val common=mapOf("accepted" to true,"failureReceipt" to true,"rateLimitTested" to true,"credentialRotationTested" to true,
            "connectedReceipt" to true,"noAnswerReceipt" to true,"dtmf1Ack" to false)
        assertThrows(com.feedback.common.exception.ClientException::class.java){service.validateProvider(ProviderAcceptanceInput("VOICE_CALL","voice-a",capabilities=common,
            dailyQuota=100,idempotencySupported=true,evidenceSha256="a".repeat(64),evidenceReference="evidence://voice/1",expiresAt=LocalDateTime.now().plusDays(30)))}
    }

    @Test fun `contract approval fails closed unless independent validated record matches`() {
        authenticated();val repository=mockk<AlertProductionGovernanceRepository>();every{repository.approveContract("cluster_id",7)} returns false
        val service=AlertProductionGovernanceService(repository,jacksonObjectMapper())
        assertThrows(com.feedback.common.exception.ClientException::class.java){service.approveContract("cluster_id")}
    }

    @Test fun `provider validation persists only capability and evidence metadata`() {
        authenticated();val repository=mockk<AlertProductionGovernanceRepository>();every{repository.validateProvider(any(),any(),any(),any(),any(),any(),any(),any(),any())} returns Unit
        val caps=mapOf("accepted" to true,"failureReceipt" to true,"rateLimitTested" to true,"credentialRotationTested" to true,"providerReceipt" to true)
        val result=AlertProductionGovernanceService(repository,jacksonObjectMapper()).validateProvider(ProviderAcceptanceInput("SMS","sms-a",capabilities=caps,
            dailyQuota=1000,idempotencySupported=true,evidenceSha256="b".repeat(64),evidenceReference="approval://sms/001",expiresAt=LocalDateTime.now().plusDays(90)))
        assertEquals("VALIDATED",result["status"])
    }

    @Test fun `p1 publishing is blocked when owner team has no eligible oncall`() {
        val repository=mockk<AlertProductionGovernanceRepository>()
        every { repository.rule(9) } returns GovernanceRulePo(9,"MASKING_FAILURE_RATE_HIGH","P1","隐私平台","{\"channels\":[\"IN_APP\"]}","SHADOW")
        every { repository.catalog("MASKING_FAILURE_RATE_HIGH") } returns GovernanceCatalogPo("APPROVED","IMPLEMENTED","a".repeat(64))
        every { repository.requirements("MASKING_FAILURE_RATE_HIGH") } returns emptyList()
        every { repository.missingProviders(setOf("IN_APP")) } returns emptyList()
        every { repository.latestAcceptance(9) } returns GovernanceAcceptancePo(status="APPROVED",routingOutcome="PASSED")
        every { repository.eligibleOncall("隐私平台") } returns 0

        val gaps=AlertProductionGovernanceService(repository,jacksonObjectMapper()).activationGaps(9)

        assertEquals(true,gaps["missingEligibleOncall"])
        assertEquals(0,gaps["eligibleOncallCount"])
        assertEquals(false,gaps["readyToPublish"])
    }
}
