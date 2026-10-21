package com.feedback.service.imports

import com.feedback.service.masking.EncryptedValue
import com.feedback.service.masking.EnvelopeCrypto
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class ImportRawCrypto(
    @Value("\${app.imports.raw-storage-secret}") secret: String,
    private val crypto: EnvelopeCrypto,
    @Value("\${app.imports.raw-storage-key-id:import-raw-v1}") val keyId: String
) {
    private val key = MessageDigest.getInstance("SHA-256").digest(secret.also {
        ensureImport(it.length >= 32) { "IMPORT_RAW_STORAGE_SECRET must contain at least 32 characters" }
    }.toByteArray(Charsets.UTF_8))
    fun encrypt(bytes: ByteArray): EncryptedValue = crypto.encrypt(bytes, key)
    fun decrypt(ciphertext: ByteArray, tag: ByteArray, nonce: ByteArray): ByteArray = crypto.decrypt(ciphertext, tag, nonce, key)
}
