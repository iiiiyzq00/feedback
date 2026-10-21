package com.feedback.service.attachment

import com.feedback.model.attachment.*

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class PlatformAttachmentStorageCryptoTest {
    @Test
    fun `small preview bytes round trip through authenticated encryption`() {
        val crypto = PlatformAttachmentStorageCrypto("preview-secret".repeat(4), "attachment-storage-v1")
        val plaintext = "first line\nsecond line\n中文日志".toByteArray()

        val ciphertext = crypto.encryptBytes(plaintext)

        assertArrayEquals(plaintext, crypto.decryptBytes(ciphertext))
    }
}
