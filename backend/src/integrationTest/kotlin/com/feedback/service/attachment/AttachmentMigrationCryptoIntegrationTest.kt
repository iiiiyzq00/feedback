package com.feedback.service.attachment

import com.feedback.common.exception.ServiceException
import com.feedback.security.attachment.PlatformAttachmentStorageCrypto
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AttachmentMigrationCryptoIntegrationTest {
    @TempDir lateinit var root: Path

    @Test
    fun `迁移解密在单文件内存上限内完整验证大附件`() {
        val plaintext = ByteArray(8 * 1024 * 1024 + 17) { index -> (index % 251).toByte() }
        val raw = root.resolve("raw.bin")
        val encrypted = root.resolve("stored.fbs")
        Files.write(raw, plaintext)
        val crypto = PlatformAttachmentStorageCrypto("a".repeat(32), "attachment-storage-v1")
        crypto.encrypt(raw, encrypted)

        val decrypted = crypto.decryptForMigration(encrypted, 16L * 1024 * 1024)
        try {
            assertArrayEquals(plaintext, decrypted)
            assertEquals(plaintext.size + 32L, Files.size(encrypted))
        } finally {
            decrypted.fill(0)
            plaintext.fill(0)
        }
    }

    @Test
    fun `迁移解密拒绝超过配置上限的附件`() {
        val raw = root.resolve("raw-small.bin")
        val encrypted = root.resolve("stored-small.fbs")
        Files.write(raw, ByteArray(4096) { 7 })
        val crypto = PlatformAttachmentStorageCrypto("b".repeat(32), "attachment-storage-v1")
        crypto.encrypt(raw, encrypted)

        val error = assertThrows(ServiceException::class.java) {
            crypto.decryptForMigration(encrypted, 1024)
        }
        assertEquals("ATTACHMENT_MIGRATION_FILE_EXCEEDS_MEMORY_LIMIT", error.reasonCode)
    }
}
