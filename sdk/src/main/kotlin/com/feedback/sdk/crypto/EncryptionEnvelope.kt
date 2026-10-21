package com.feedback.sdk.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 加密信封
 *
 * 每条反馈生成随机 256-bit 主数据密钥，
 * 使用 HKDF 派生 description、附件、日志的用途子密钥。
 * 主密钥由服务端 RSA-OAEP-256 公钥封装。
 */
object EncryptionEnvelope {

    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val AES_KEY_LENGTH_BYTES = 32

    private val secureRandom = SecureRandom()

    /**
     * 生成随机 AES-256 密钥
     */
    fun generateDataKey(): ByteArray {
        val key = ByteArray(AES_KEY_LENGTH_BYTES)
        secureRandom.nextBytes(key)
        return key
    }

    /**
     * 生成随机 nonce (12 bytes for GCM)
     */
    fun generateNonce(): ByteArray {
        val nonce = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    /** HKDF-SHA256 用途子密钥，避免不同数据用途直接复用主密钥。 */
    fun derivePurposeKey(dataKey: ByteArray, purpose: String): ByteArray {
        require(dataKey.size == AES_KEY_LENGTH_BYTES) { "dataKey must be 32 bytes" }
        require(purpose.isNotBlank()) { "purpose must not be blank" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("feedback-sdk-hkdf-v1".toByteArray(), "HmacSHA256"))
        val prk = mac.doFinal(dataKey)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        return mac.doFinal(purpose.toByteArray() + byteArrayOf(1))
    }

    /**
     * AES-256-GCM 加密
     * @return Pair(ciphertext, tag) 其中 tag 为 16 bytes
     */
    fun encrypt(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): EncryptResult {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
        val output = cipher.doFinal(plaintext)
        // GCM 模式下 doFinal 输出 = ciphertext + tag (最后16字节)
        val tagLength = GCM_TAG_LENGTH_BITS / 8
        val ciphertext = output.copyOfRange(0, output.size - tagLength)
        val tag = output.copyOfRange(output.size - tagLength, output.size)
        return EncryptResult(ciphertext, tag)
    }

    /**
     * AES-256-GCM 解密
     */
    fun decrypt(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray, tag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
        // 合并 ciphertext + tag
        val combined = ciphertext + tag
        return cipher.doFinal(combined)
    }

    data class EncryptResult(
        val ciphertext: ByteArray,
        val tag: ByteArray
    )
}
