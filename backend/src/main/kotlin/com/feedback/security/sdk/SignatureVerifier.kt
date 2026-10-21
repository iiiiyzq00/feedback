package com.feedback.security.sdk

import com.feedback.service.sdk.*

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import kotlin.math.abs
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import com.feedback.config.SdkAuthProperties
import com.feedback.config.RuntimeConfigService

/**
 * HMAC-SHA256 签名校验器
 *
 * 签名公式: HMAC-SHA256(app_secret, METHOD+PATH+timestamp+nonce+sha256(body))
 *
 * 安全原则：
 * - appKey 是公开的应用标识（X-App-Key 头）
 * - appSecret 是只有 SDK 和服务端知道的共享密钥（签名用）
 * - appKey ≠ appSecret，防止标识泄露直接导致签名伪造
 */
@Service
class SignatureVerifier(
    private val properties: SdkAuthProperties,
    private val clock: Clock = Clock.systemUTC(), private val runtimeConfig: RuntimeConfigService? = null
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 按规范化请求串校验 SDK HMAC；使用常量时间比较，签名格式错误或密钥缺失均失败关闭。 */
    fun verify(
        appKey: String,
        timestamp: String,
        nonce: String,
        signature: String,
        method: String,
        path: String,
        bodySha256: String
    ): Boolean {
        val requestTimeMillis = timestamp.toLongOrNull() ?: return false
        if (abs(clock.millis() - requestTimeMillis) > (runtimeConfig?.int("sdk.sign-window-seconds",300) ?: 300) * 1000L) {
            log.warn("SDK timestamp outside allowed window: appKey={}", appKey)
            return false
        }

        val secret = properties.appSecrets[appKey]
        if (secret == null) {
            log.warn("Unknown app_key: $appKey — 请检查 SDK 配置")
            return false
        }

        val signContent = "$method$path$timestamp$nonce$bodySha256"
        val expected = hmacSha256(signContent, secret)

        val match = MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            signature.toByteArray(Charsets.UTF_8)
        )

        if (!match) {
            log.warn("HMAC 签名不匹配: appKey={}", appKey)
        }
        return match
    }

    companion object {
        /** 构造仅供测试使用的签名校验器，显式注入时钟与密钥，避免依赖生产配置。 */
        fun forTest(devAppSecret: String, clock: Clock = Clock.systemUTC()): SignatureVerifier =
            SignatureVerifier(
                SdkAuthProperties().apply { appSecrets = mapOf("dev-app-key" to devAppSecret) },
                clock
            )
    }

    /** 计算 HMAC-SHA256 签名；密钥和中间字节不得写入日志或持久化。 */
    private fun hmacSha256(data: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
