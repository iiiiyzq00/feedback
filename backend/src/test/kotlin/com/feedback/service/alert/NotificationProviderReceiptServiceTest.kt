package com.feedback.service.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.repository.alert.NotificationProviderReceiptRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class NotificationProviderReceiptServiceTest {
    private val secret="receipt-secret-at-least-thirty-two-bytes"

    @Test fun `signed DTMF one receipt is accepted without closing incident`() {
        val repository=mockk<NotificationProviderReceiptRepository>(relaxed=true);every{repository.notificationId(any(),any())} returns 9L;every{repository.record(any(),any(),any(),any(),any(),any(),any(),any(),any())} returns true;every{repository.isLatest(9L,"voice-a","callback-0001")} returns true;every{repository.markDelivered(9)} returns true
        val mapper=jacksonObjectMapper().findAndRegisterModules();val service=NotificationProviderReceiptService(repository,mapper,"""{"voice-a":"$secret"}""")
        val now=Instant.now();val body=mapper.writeValueAsString(ProviderReceiptInput("callback-0001","voice-a","VOICE_CALL","message-1","DTMF_ACK",now,"1"));val timestamp=now.epochSecond.toString()
        val result=service.accept(body,timestamp,hmac("$timestamp\n$body"))
        assertEquals(false,result["incidentAcknowledged"]);assertEquals(true,result["notificationMatched"]);assertEquals(true,result["notificationStateApplied"])
        verify{repository.record("callback-0001","voice-a","VOICE_CALL","message-1",9L,"DTMF_ACK","1",any(),any())}
        verify{repository.markDelivered(9L)}
    }

    @Test fun `out of order failure remains audited without changing state or escalation`() {
        val repository=mockk<NotificationProviderReceiptRepository>(relaxed=true);every{repository.notificationId(any(),any())} returns 9L;every{repository.record(any(),any(),any(),any(),any(),any(),any(),any(),any())} returns true;every{repository.isLatest(9L,"voice-a","callback-older")} returns false
        val escalation=mockk<NotificationEscalationService>(relaxed=true);val mapper=jacksonObjectMapper().findAndRegisterModules();val service=NotificationProviderReceiptService(repository,mapper,"""{"voice-a":"$secret"}""",escalation)
        val now=Instant.now();val body=mapper.writeValueAsString(ProviderReceiptInput("callback-older","voice-a","VOICE_CALL","message-1","NO_ANSWER",now.minusSeconds(30)));val timestamp=now.epochSecond.toString()

        val result=service.accept(body,timestamp,hmac("$timestamp\n$body"))

        assertEquals(false,result["duplicate"]);assertEquals(true,result["notificationMatched"])
        verify{repository.record("callback-older","voice-a","VOICE_CALL","message-1",9L,"NO_ANSWER",null,any(),any())}
        verify(exactly=0){repository.markFailed(any(),any());escalation.notificationFailed(any(),any())}
    }

    @Test fun `failure advances escalation only when notification state transition succeeds`() {
        val repository=mockk<NotificationProviderReceiptRepository>(relaxed=true);every{repository.notificationId(any(),any())} returns 9L;every{repository.record(any(),any(),any(),any(),any(),any(),any(),any(),any())} returns true;every{repository.isLatest(9L,"voice-a","callback-0003")} returns true;every{repository.markFailed(9L,"BOUNCED")} returns false
        val escalation=mockk<NotificationEscalationService>(relaxed=true);val mapper=jacksonObjectMapper().findAndRegisterModules();val service=NotificationProviderReceiptService(repository,mapper,"""{"voice-a":"$secret"}""",escalation)
        val now=Instant.now();val body=mapper.writeValueAsString(ProviderReceiptInput("callback-0003","voice-a","EMAIL","message-3","BOUNCED",now));val timestamp=now.epochSecond.toString()

        service.accept(body,timestamp,hmac("$timestamp\n$body"))

        verify{repository.markFailed(9L,"BOUNCED")}
        verify(exactly=0){escalation.notificationFailed(any(),any())}
    }

    @Test fun `latest delivered receipt reports when local notification state was not applied`() {
        val repository=mockk<NotificationProviderReceiptRepository>(relaxed=true);every{repository.notificationId(any(),any())} returns 9L;every{repository.record(any(),any(),any(),any(),any(),any(),any(),any(),any())} returns true;every{repository.isLatest(9L,"mail-a","callback-0004")} returns true;every{repository.markDelivered(9)} returns false
        val mapper=jacksonObjectMapper().findAndRegisterModules();val service=NotificationProviderReceiptService(repository,mapper,"""{"mail-a":"$secret"}""")
        val now=Instant.now();val body=mapper.writeValueAsString(ProviderReceiptInput("callback-0004","mail-a","EMAIL","message-4","DELIVERED",now));val timestamp=now.epochSecond.toString()

        val result=service.accept(body,timestamp,hmac("$timestamp\n$body"))

        assertEquals(true,result["accepted"]);assertEquals(true,result["notificationMatched"]);assertEquals(false,result["notificationStateApplied"])
    }

    @Test fun `invalid provider signature is rejected before database write`() {
        val repository=mockk<NotificationProviderReceiptRepository>(relaxed=true);val mapper=jacksonObjectMapper().findAndRegisterModules();val now=Instant.now()
        val body=mapper.writeValueAsString(ProviderReceiptInput("callback-0002","voice-a","VOICE_CALL","message-2","CONNECTED",now))
        assertThrows(com.feedback.common.exception.ClientException::class.java){NotificationProviderReceiptService(repository,mapper,"""{"voice-a":"$secret"}""").accept(body,now.epochSecond.toString(),"0".repeat(64))}
        verify(exactly=0){repository.record(any(),any(),any(),any(),any(),any(),any(),any(),any())}
    }
    private fun hmac(value:String)=Mac.getInstance("HmacSHA256").run{init(SecretKeySpec(secret.toByteArray(),"HmacSHA256"));doFinal(value.toByteArray()).joinToString(""){"%02x".format(it)}}
}
