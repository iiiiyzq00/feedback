package com.feedback.service.attachment

import com.feedback.model.attachment.*

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

class AttachmentObjectStorageMigrationTest {
    @Test
    fun `V236 adds nullable object location and lifecycle facts without switching legacy storage`() {
        val migration = Path.of("src/main/resources/db/migration/V236__attachment_object_storage_contract.sql")
        val sql = Files.readString(migration)

        listOf(
            "storage_backend", "object_bucket", "object_key", "object_version", "object_etag",
            "object_storage_status", "object_migrated_at", "S3_COMPATIBLE", "storage_path"
        ).forEach { token -> assertTrue(sql.contains(token), token) }
        assertTrue(sql.contains("CHECK"))
        assertTrue(sql.contains("ADD KEY idx_attachment_object_storage_work"))
        assertEquals(1_559_985_492, flywayChecksum(migration))
        assertFalse(sql.contains("UPDATE ", ignoreCase = true))
        assertFalse(sql.contains("DELETE ", ignoreCase = true))
        assertFalse(sql.startsWith("version https://git-lfs.github.com/spec"))
    }

    /** 冻结专用库已成功应用的 V236 内容；业务语义变化必须通过新的前向迁移表达。 */
    private fun flywayChecksum(migration: Path): Int {
        val checksum = CRC32()
        Files.readAllLines(migration, StandardCharsets.UTF_8).forEach { line ->
            checksum.update(line.toByteArray(StandardCharsets.UTF_8))
        }
        return checksum.value.toInt()
    }
}
