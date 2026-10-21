package com.feedback.service.masking

import org.springframework.stereotype.Component
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class EnvelopeCrypto {
    fun decrypt(ciphertext: ByteArray, tag: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        if(key.size != 32 || nonce.size != 12 || tag.size != 16) maskingDecryptFailed("MASKING_ENVELOPE_INVALID")
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(ciphertext + tag)
        }
    }

    fun derivePurposeKey(dataKey: ByteArray, purpose: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("feedback-sdk-hkdf-v1".toByteArray(), "HmacSHA256"))
        val prk = mac.doFinal(dataKey)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        return mac.doFinal(purpose.toByteArray() + byteArrayOf(1))
    }

    fun encrypt(plaintext: ByteArray, key: ByteArray): EncryptedValue {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val output = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(plaintext)
        }
        return EncryptedValue(output.copyOfRange(0, output.size - 16), nonce, output.copyOfRange(output.size - 16, output.size))
    }
}

data class EncryptedValue(val ciphertext: ByteArray, val nonce: ByteArray, val tag: ByteArray)
