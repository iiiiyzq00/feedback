package com.feedback.service.masking

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class ContactStorageCrypto(
    @Value("\${app.masking.contact-storage-secret}") secret: String,
    private val crypto: EnvelopeCrypto,
    @Value("\${app.masking.contact-storage-key-id:contact-storage-v1}") val keyId: String
) {
    private val key = MessageDigest.getInstance("SHA-256").digest(secret.also {
        if(it.length < 32) maskingFailed("CONTACT_STORAGE_SECRET_INVALID")
    }.toByteArray(Charsets.UTF_8))

    fun encrypt(value: String): EncryptedValue = crypto.encrypt(value.toByteArray(Charsets.UTF_8), key)
    fun decrypt(ciphertext: ByteArray, tag: ByteArray, nonce: ByteArray): String =
        crypto.decrypt(ciphertext, tag, nonce, key).let { bytes ->
            try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
        }
}
