package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.alert.DeadLetterNotificationPo
import com.feedback.model.po.alert.DeliveryAttemptPo
import com.feedback.model.po.alert.DeliveryEvidencePo
import com.feedback.repository.alert.DeadLetterRepository
import com.feedback.security.WebPrincipal
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class DeadLetterServiceTest {
    private val repository = mockk<DeadLetterRepository>(relaxed = true)
    private val service = DeadLetterService(repository, ObjectMapper())

    @AfterEach fun clearContext() = SecurityContextHolder.clearContext()

    @Test fun `detail keeps notification attempts and evidence sections`() {
        every { repository.notification(7) } returns DeadLetterNotificationPo(id=7,ruleId=3,channel="SMS",status="DEAD_LETTER",attempts=5,maxRetries=5)
        every { repository.attempts(7) } returns listOf(DeliveryAttemptPo(id=11,attempt=1,status="FAILED"))
        every { repository.evidence(7) } returns listOf(DeliveryEvidencePo(id=12,type="PROVIDER_RECEIPT",reference="receipt-1"))
        val result=service.detail(7)
        assertEquals(7L,result["id"]);assertEquals(1,(result["attemptHistory"] as List<*>).size);assertEquals(1,(result["evidence"] as List<*>).size)
    }

    @Test fun `replay uses guarded transition then writes authenticated audit`() {
        authenticate(9);every { repository.replay(7) } returns true
        assertEquals(true,service.replay(7,DeadLetterAction("人工确认重试"))["replayed"])
        verify { repository.audit(9,"ALERT_NOTIFICATION_REPLAY",7,match { "人工确认重试" in it }) }
    }

    @Test fun `failed replay does not write audit`() {
        authenticate(9);every { repository.replay(7) } returns false
        assertEquals("DEAD_LETTER_NOT_REPLAYABLE",assertThrows(com.feedback.common.exception.ClientException::class.java){service.replay(7,DeadLetterAction("不可重试"))}.reasonCode)
        verify(exactly=0){repository.audit(any(),any(),any(),any())}
    }

    private fun authenticate(id:Long){
        val principal=WebPrincipal(id,"operator","","Operator",setOf("OPERATOR"),emptySet())
        SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(principal,"")
    }
}
