package com.feedback.security.attachment

import com.feedback.service.attachment.*

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.net.Socket

/** ClamAV 可用性探针；只执行短连接 VERSION 命令，不扫描业务文件。 */
@Component("clamAv")
class ClamAvHealthIndicator(
    @Value("\${app.attachment.security-scan.enabled:false}") private val enabled: Boolean,
    @Value("\${app.attachment.security-scan.clamav-host:127.0.0.1}") private val host: String,
    @Value("\${app.attachment.security-scan.clamav-port:3310}") private val port: Int,
    @Value("\${app.attachment.security-scan.timeout-ms:5000}") private val timeoutMs: Int
) : HealthIndicator {
    /** 返回探针健康状态；禁用、协议异常和网络失败分别给出稳定原因。 */
    override fun health(): Health {
        if (!enabled) {
            return Health.unknown().withDetail("reason", "disabled").build()
        }
        return runCatching {
            val response = command("zVERSION\u0000")
            if (!response.startsWith("ClamAV ")) {
                return@runCatching Health.down()
                    .withDetail("endpoint", "$host:$port")
                    .withDetail("reason", "invalid response")
                    .build()
            }
            Health.up().withDetail("endpoint", "$host:$port").withDetail("version", response.take(200)).build()
        }.getOrElse {
            Health.down().withDetail("endpoint", "$host:$port").withDetail("reason", "unavailable").build()
        }
    }

    /** 以有界连接和读取超时执行单条 ClamAV 控制命令。 */
    private fun command(value: String): String = Socket().use { socket ->
        val timeout = timeoutMs.coerceIn(500, 30_000)
        socket.connect(InetSocketAddress(host, port), timeout)
        socket.soTimeout = timeout
        socket.getOutputStream().apply {
            write(value.toByteArray(Charsets.US_ASCII))
            flush()
        }
        socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty().trimEnd('\u0000')
    }
}
