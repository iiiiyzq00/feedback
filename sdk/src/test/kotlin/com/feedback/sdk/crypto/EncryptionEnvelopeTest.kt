package com.feedback.sdk.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EncryptionEnvelopeTest {
    @Test
    fun `encrypt decrypt round trip`() {
        val key = EncryptionEnvelope.generateDataKey()
        val nonce = EncryptionEnvelope.generateNonce()
        val plaintext = "已脱敏反馈".toByteArray()
        val encrypted = EncryptionEnvelope.encrypt(plaintext, key, nonce)

        assertArrayEquals(
            plaintext,
            EncryptionEnvelope.decrypt(encrypted.ciphertext, key, nonce, encrypted.tag)
        )
    }

    @Test
    fun `purpose keys are deterministic and isolated`() {
        val key = ByteArray(32) { it.toByte() }
        assertArrayEquals(
            EncryptionEnvelope.derivePurposeKey(key, "contact"),
            EncryptionEnvelope.derivePurposeKey(key, "contact")
        )
        assertFalse(
            EncryptionEnvelope.derivePurposeKey(key, "contact")
                .contentEquals(EncryptionEnvelope.derivePurposeKey(key, "payload"))
        )
    }
}
