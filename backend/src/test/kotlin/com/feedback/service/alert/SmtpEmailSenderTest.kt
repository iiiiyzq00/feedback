package com.feedback.service.alert

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import jakarta.mail.internet.MimeMultipart

class SmtpEmailSenderTest {
    private val payload=EmailWebhookPayload("evt-1","ALERT_FIRED",listOf("receiver@qq.com"),"[P1] 告警","正文","<p>正文</p>",emptyMap())

    @Test fun `qq smtps configuration is accepted`() {
        val sender=SmtpEmailSender("smtp.qq.com",465,true,false,"sender@qq.com","authorization-code","sender@qq.com",5000,10000,10000)
        assertTrue(sender.valid(payload))
    }

    @Test fun `missing auth invalid recipients and ambiguous tls are rejected`() {
        assertFalse(SmtpEmailSender("smtp.qq.com",465,true,false,"sender@qq.com","","sender@qq.com",5000,10000,10000).valid(payload))
        assertFalse(SmtpEmailSender("smtp.qq.com",465,true,true,"sender@qq.com","code","sender@qq.com",5000,10000,10000).valid(payload))
        val invalid=payload.copy(recipients=listOf("not-an-email"))
        assertFalse(SmtpEmailSender("smtp.qq.com",465,true,false,"sender@qq.com","code","sender@qq.com",5000,10000,10000).valid(invalid))
    }

    @Test fun `mime message has stable id event headers and alternative bodies`() {
        val sender=SmtpEmailSender("smtp.qq.com",465,true,false,"sender@qq.com","authorization-code","sender@qq.com",5000,10000,10000)
        val first=sender.buildMessage(payload);val second=sender.buildMessage(payload)
        assertEquals("<evt-1@feedback-alert.local>",first.getHeader("Message-ID",null))
        assertEquals(first.getHeader("Message-ID",null),second.getHeader("Message-ID",null))
        assertEquals("evt-1",first.getHeader("X-Alert-Event-Id",null))
        val parts=first.content as MimeMultipart
        assertEquals(2,parts.count);assertTrue(parts.getBodyPart(0).contentType.startsWith("text/plain"));assertTrue(parts.getBodyPart(1).contentType.startsWith("text/html"))
    }
}
