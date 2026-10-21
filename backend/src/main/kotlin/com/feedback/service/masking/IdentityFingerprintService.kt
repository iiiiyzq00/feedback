package com.feedback.service.masking

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
class IdentityFingerprintService(
    @Value("\${app.masking.fingerprint-secret}") private val secret: String,
    @Value("\${app.masking.fingerprint-key-id:identity-hmac-v1}") val keyId: String
) {
    companion object { const val NORMALIZATION_VERSION = "contact-normalization-sql-v1" }
    init { if(secret.length < 32) maskingFailed("MASKING_FINGERPRINT_SECRET_INVALID") }

    fun normalize(value: String, type: String): String = normalizeCanonical(value, type)

    /** 供预校验的有界批次与不依赖 MySQL 的独立测试使用的确定性规范形式。 */
    fun normalizeCanonical(value: String, type: String): String = when (type.uppercase()) {
        "PHONE" -> value.filter(Char::isDigit).removePrefix("86")
        "EMAIL" -> value.trim().lowercase()
        "DEVICE_SN" -> value.filter(Char::isLetterOrDigit).uppercase()
        else -> value.trim()
    }

    fun fingerprint(value: String, type: String): ByteArray {
        return fingerprintCanonical(normalize(value, type), type)
    }

    /**
     * 为已经由本组件归一化的值生成受治理的 HMAC。
     * 有界导入器走这条路径，以免把预校验批次退化成逐行的归一化查询。
     */
    fun fingerprintCanonical(value: String, type: String): ByteArray {
        if(value != normalizeCanonical(value, type)) maskingInvalid("IDENTITY_VALUE_NOT_CANONICAL")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("$type:$value".toByteArray(Charsets.UTF_8))
    }
}
