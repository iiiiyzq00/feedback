package com.feedback.service.sdk

import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.strategy.sdk.*
import com.feedback.security.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SdkAttachmentStateMigrationTest {
    @Test
    fun `forward migration only advances decrypted SDK evidence into scanner claimable state`() {
        val sql = Files.readString(Path.of(
            "src/main/resources/db/migration/V227__release_verified_sdk_attachments_to_security_scan.sql"
        ))

        assertTrue(sql.contains("source_kind = 'SDK'"))
        assertTrue(sql.contains("upload_status = 'UPLOADED'"))
        assertTrue(sql.contains("integrity_status = 'VERIFIED'"))
        assertTrue(sql.contains("decryption_status = 'DECRYPTED'"))
        assertTrue(sql.contains("security_status = 'QUARANTINED'"))
        assertTrue(sql.contains("SET upload_status = 'VERIFIED'"))
        assertFalse(sql.startsWith("version https://git-lfs.github.com/spec"))
        assertFalse(sql.contains("DELETE ", ignoreCase = true))
    }
}
