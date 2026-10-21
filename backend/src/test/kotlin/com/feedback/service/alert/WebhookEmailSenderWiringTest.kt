package com.feedback.service.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer
import org.springframework.core.env.MapPropertySource

/**
 * `WebhookEmailSender` 现在有两条构造器：带 `@Autowired` 的配置构造器（ObjectMapper/String/Long），
 * 和承载传输层的主构造器（ObjectMapper/String/OkHttpClient）。
 *
 * Spring 必须挑中前者。挑错的话**不是**编译错误、也不是任何单测会红 ——
 * 是 `provider=webhook` 的部署在启动时才发现 `OkHttpClient` 没有可用 Bean。
 * 所以这里真的起一个容器来验，而不是靠「注解写对了应该没问题」。
 *
 * 断言用的是**行为**而不是反射：把 webhook-url 配成非 HTTPS，
 * `send()` 必须返回 `CONFIGURATION` —— 这同时证明容器建出了这个 Bean，且 url 确实被注入。
 */
class WebhookEmailSenderWiringTest {
    private val payload=EmailWebhookPayload("evt-wiring","ALERT_FIRED",listOf("ops@example.com"),"告警","正文","<p>正文</p>",emptyMap())

    @Test fun `spring builds the configured sender and binds the webhook url`() {
        val context=AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(MapPropertySource("test",mapOf(
            "app.alert.email.provider" to "webhook",
            "app.alert.email.webhook-url" to "http://not-https.example.com/mail",
            "app.alert.email.webhook-timeout-ms" to "1234"
        )))
        context.beanFactory.registerSingleton("objectMapper",jacksonObjectMapper())
        context.register(PropertySourcesPlaceholderConfigurer::class.java)
        context.register(WebhookEmailSender::class.java)
        context.use {
            context.refresh()
            val sender=context.getBean(WebhookEmailSender::class.java)
            assertEquals("CONFIGURATION",sender.send(payload).errorType,
                "url 未被注入 —— Spring 很可能挑了传 OkHttpClient 的那条构造器")
        }
    }
}
