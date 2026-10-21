package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.Date
import java.util.Properties

interface EmailSender {
    fun send(payload: EmailWebhookPayload): DeliveryResult
}

@Service
@ConditionalOnProperty(prefix = "app.alert.email", name = ["provider"], havingValue = "smtp")
class SmtpEmailSender(
    @Value("\${app.alert.email.smtp.host:}") private val host: String,
    @Value("\${app.alert.email.smtp.port:465}") private val port: Int,
    @Value("\${app.alert.email.smtp.ssl:true}") private val ssl: Boolean,
    @Value("\${app.alert.email.smtp.starttls:false}") private val startTls: Boolean,
    @Value("\${app.alert.email.smtp.username:}") private val username: String,
    @Value("\${app.alert.email.smtp.auth-code:}") private val authCode: String,
    @Value("\${app.alert.email.from:}") private val from: String,
    @Value("\${app.alert.email.connect-timeout-ms:5000}") private val connectTimeoutMs: Int,
    @Value("\${app.alert.email.read-timeout-ms:10000}") private val readTimeoutMs: Int,
    @Value("\${app.alert.email.write-timeout-ms:10000}") private val writeTimeoutMs: Int
) : EmailSender {
    override fun send(payload: EmailWebhookPayload): DeliveryResult {
        if (!valid(payload)) return DeliveryResult.failure("CONFIGURATION", "SMTP配置或收件人无效")
        return try {
            val message = buildMessage(payload)
            Transport.send(message)
            DeliveryResult.success(providerCode = "SMTP_ACCEPTED", providerMessageId = message.messageID)
        } catch (error: Exception) {
            val type = when {
                error.causeChain().any { it is java.net.UnknownHostException } -> "DNS_FAILURE"
                error.causeChain().any { it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException } -> "TIMEOUT"
                error.causeChain().any { it is javax.net.ssl.SSLHandshakeException } -> "TLS_CERTIFICATE"
                error.javaClass.name.contains("AuthenticationFailed") -> "AUTHENTICATION"
                else -> "SMTP_FAILURE"
            }
            DeliveryResult.failure(type, safeSummary(type))
        }
    }

    internal fun buildMessage(payload: EmailWebhookPayload): MimeMessage = MimeMessage(session()).apply {
        setFrom(InternetAddress(this@SmtpEmailSender.from, false))
        setRecipients(Message.RecipientType.TO, payload.recipients.map(::InternetAddress).toTypedArray())
        subject = payload.subject
        sentDate = Date()
        setHeader("X-Alert-Event-Id", payload.eventId)
        setHeader("X-Alert-Event-Type", payload.eventType)
        setContent(MimeMultipart("alternative").apply {
            addBodyPart(MimeBodyPart().apply { setText(payload.text, Charsets.UTF_8.name()) })
            addBodyPart(MimeBodyPart().apply { setContent(payload.html, "text/html; charset=UTF-8") })
        })
        saveChanges()
        setHeader("Message-ID", "<${safeEventId(payload.eventId)}@feedback-alert.local>")
    }

    internal fun valid(payload: EmailWebhookPayload): Boolean = host.isNotBlank() && port in 1..65535 &&
        username.isNotBlank() && authCode.isNotBlank() && from.matches(EMAIL) && payload.recipients.isNotEmpty() &&
        payload.recipients.all { it.matches(EMAIL) } && connectTimeoutMs in 1000..60000 &&
        readTimeoutMs in 1000..120000 && writeTimeoutMs in 1000..120000 && (ssl xor startTls)

    private fun session(): Session {
        val properties = Properties().apply {
            put("mail.transport.protocol", "smtp"); put("mail.smtp.host", host); put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true"); put("mail.smtp.ssl.enable", ssl.toString()); put("mail.smtp.starttls.enable", startTls.toString())
            put("mail.smtp.starttls.required", startTls.toString()); put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.connectiontimeout", connectTimeoutMs.toString()); put("mail.smtp.timeout", readTimeoutMs.toString())
            put("mail.smtp.writetimeout", writeTimeoutMs.toString()); put("mail.smtp.quitwait", "false")
        }
        return Session.getInstance(properties, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(username, authCode)
        })
    }

    private fun safeEventId(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "-").take(120)

    companion object { private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") }
}

@Service
@ConditionalOnProperty(prefix = "app.alert.email", name = ["provider"], havingValue = "webhook")
class WebhookEmailSender internal constructor(
    private val mapper: ObjectMapper,
    private val url: String,
    private val client: OkHttpClient
) : EmailSender {

    @Autowired
    constructor(
        mapper: ObjectMapper,
        @Value("\${app.alert.email.webhook-url:}") url: String,
        @Value("\${app.alert.email.webhook-timeout-ms:8000}") timeoutMs: Long
    ) : this(mapper, url, webhookClient(timeoutMs))

    override fun send(payload: EmailWebhookPayload): DeliveryResult {
        if (!url.startsWith("https://")) return DeliveryResult.failure("CONFIGURATION", "邮件Webhook必须使用HTTPS")
        return try {
            client.newCall(Request.Builder().url(url).header("Idempotency-Key", payload.eventId)
                .post(mapper.writeValueAsString(payload).toRequestBody("application/json".toMediaType())).build())
                .execute().use { response ->
                    if (response.isSuccessful) DeliveryResult.success(response.code, "HTTP_ACCEPTED")
                    else DeliveryResult.failure(httpError(response.code), "邮件网关返回HTTP ${response.code}", response.code)
                }
        } catch (error: Exception) {
            val type = networkError(error)
            DeliveryResult.failure(type, safeSummary(type))
        }
    }
}

/**
 * 生产用的传输层：连接与整次调用都按配置超时。
 *
 * 把 `mapper` / `url` / `client` 做成构造参数而不是就地 `new`，是为了让**错误分类**这件事
 * 能脱离宿主机网络单独验证（见 `WebhookEmailSenderTest` 里对 DNS 的显式注入）。
 * 起因：本机把**任意**域名通配解析到 `127.0.0.1` —— 连 `mail.invalid` 都能解析成功，
 * 之后连接被拒，于是「拿一个不存在的域名制造 DNS 失败」得到的是连接失败。
 * 那个写法验的其实是宿主机的解析器，不是本类的分类逻辑。
 */
private fun webhookClient(timeoutMs: Long): OkHttpClient {
    val timeout = Duration.ofMillis(timeoutMs.coerceIn(100, 30_000))
    return OkHttpClient.Builder().connectTimeout(timeout).callTimeout(timeout).build()
}

@Service
@ConditionalOnProperty(prefix = "app.alert.email", name = ["provider"], havingValue = "disabled", matchIfMissing = true)
class DisabledEmailSender : EmailSender {
    override fun send(payload: EmailWebhookPayload) = DeliveryResult.failure("CONFIGURATION", "邮件发送器未配置")
}

internal fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }
internal fun networkError(error: Throwable): String = when {
    error.causeChain().any { it is java.net.UnknownHostException } -> "DNS_FAILURE"
    error.causeChain().any { it is java.net.SocketTimeoutException || it is java.io.InterruptedIOException } -> "TIMEOUT"
    error.causeChain().any { it is javax.net.ssl.SSLHandshakeException } -> "TLS_CERTIFICATE"
    else -> "NETWORK_FAILURE"
}
internal fun httpError(status: Int) = when (status) { 429 -> "HTTP_429"; in 500..599 -> "HTTP_5XX"; else -> "HTTP_$status" }
internal fun safeSummary(type: String) = when (type) {
    "DNS_FAILURE" -> "域名解析失败"; "TIMEOUT" -> "网络请求超时"; "TLS_CERTIFICATE" -> "TLS证书验证失败"
    "AUTHENTICATION" -> "SMTP认证失败"; else -> "邮件投递失败"
}
