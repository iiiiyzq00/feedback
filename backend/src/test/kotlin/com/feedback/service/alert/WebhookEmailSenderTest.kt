package com.feedback.service.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration

/**
 * 传输层全部由测试注入（见 `WebhookEmailSender` 的 internal 主构造器），
 * 所以这几条断言在任何机器上验的都是**本类的分类逻辑**，而不是宿主机的网络行为。
 *
 * 起因：原先 DNS 那条写的是 `WebhookEmailSender(mapper,"https://mail.invalid/send",300)`，
 * 依赖「`.invalid` 解析不出 IP」。本机把任意域名通配解析到 127.0.0.1，
 * 于是解析成功、连接被拒，拿到的是 NETWORK_FAILURE，断言恒假。
 */
class WebhookEmailSenderTest {
    private val mapper=jacksonObjectMapper()
    private val payload=EmailWebhookPayload("evt-http","ALERT_FIRED",listOf("ops@example.com"),"告警","正文","<p>正文</p>",emptyMap())

    @Test fun `429 and 5xx are classified with http evidence`() {
        tlsServer(MockResponse().setResponseCode(429),trustClient=true).use { fixture ->
            val result=fixture.sender.send(payload);assertEquals("HTTP_429",result.errorType);assertEquals(429,result.httpStatus)
        }
        tlsServer(MockResponse().setResponseCode(503),trustClient=true).use { fixture ->
            val result=fixture.sender.send(payload);assertEquals("HTTP_5XX",result.errorType);assertEquals(503,result.httpStatus)
        }
    }

    @Test fun `timeout dns and invalid certificate are classified without leaking endpoint`() {
        tlsServer(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),trustClient=true,timeout=150).use { fixture -> assertEquals("TIMEOUT",fixture.sender.send(payload).errorType) }
        val dnsFailure=WebhookEmailSender(mapper,"https://mail.invalid/send",throwingDnsClient()).send(payload)
        assertEquals("DNS_FAILURE",dnsFailure.errorType)
        assertFalse(dnsFailure.errorSummary!!.contains("mail.invalid"),"错误摘要不得泄露端点：${dnsFailure.errorSummary}")
        tlsServer(MockResponse().setResponseCode(200),trustClient=false).use { fixture -> assertEquals("TLS_CERTIFICATE",fixture.sender.send(payload).errorType) }
    }

    @Test fun `same endpoint automatically succeeds on next retry after recovery`() {
        tlsServer(MockResponse().setResponseCode(503),trustClient=true).use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(200))
            assertFalse(fixture.sender.send(payload).delivered);assertTrue(fixture.sender.send(payload).delivered)
            assertEquals("evt-http",fixture.server.takeRequest().getHeader("Idempotency-Key"))
        }
    }

    private fun tlsServer(first:MockResponse,trustClient:Boolean,timeout:Long=500):Fixture {
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates=HandshakeCertificates.Builder().heldCertificate(cert).build()
        val server=MockWebServer();server.useHttps(serverCertificates.sslSocketFactory(),false);server.enqueue(first);server.start()
        val trust=if(trustClient)HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build() else null
        return Fixture(server,WebhookEmailSender(mapper,server.url("/mail").toString(),client(timeout,trust)))
    }

    private fun client(timeout:Long,trust:HandshakeCertificates?):OkHttpClient {
        val builder=OkHttpClient.Builder().connectTimeout(Duration.ofMillis(timeout)).callTimeout(Duration.ofMillis(timeout)).dns(loopbackDns())
        if(trust!=null)builder.sslSocketFactory(trust.sslSocketFactory(),trust.trustManager)
        return builder.build()
    }

    /** 显式解析到 127.0.0.1，让「能否连上」只取决于 MockWebServer，不取决于宿主机解析器。 */
    private fun loopbackDns()=object:Dns{override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1"))}

    /** 解析必然失败。用它构造 DNS 失败，而不是指望某个域名在宿主机上真的解析不出来。 */
    private fun throwingDnsClient()=OkHttpClient.Builder()
        .dns(object:Dns{override fun lookup(hostname:String):List<InetAddress> = throw UnknownHostException(hostname)}).build()

    private data class Fixture(val server:MockWebServer,val sender:EmailSender):AutoCloseable{override fun close()=server.close()}
}
