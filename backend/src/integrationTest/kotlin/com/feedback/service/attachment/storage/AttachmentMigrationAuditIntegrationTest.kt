package com.feedback.service.attachment.storage

import com.feedback.config.AttachmentObjectStorageProperties
import com.feedback.model.po.attachment.AttachmentObjectCleanupCandidatePo
import com.feedback.model.attachment.AttachmentCleanupAuditOutcome
import com.feedback.repository.impl.attachment.JsonLineAttachmentMigrationAudit
import com.feedback.security.attachment.AttachmentLocalPathPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AttachmentMigrationAuditIntegrationTest {
    @TempDir lateinit var root: Path

    @Test
    fun `删除意图只有在后续事件缺失时才保持未闭合`() {
        val properties = AttachmentObjectStorageProperties().apply {
            cleanupAuditPath = root.resolve("migration.jsonl").toString()
        }
        val audit = JsonLineAttachmentMigrationAudit(properties)
        val candidate = AttachmentObjectCleanupCandidatePo(
            id = 7,
            storagePath = root.resolve("7.fbs").toString(),
            fileSizeBytes = 3,
            fileSha256 = ByteArray(32) { 1 },
            objectBucket = "feedback-private",
            objectKey = "attachments/sdk/7/value.bin",
            objectVersion = "version-1",
            objectEtag = "etag",
            objectStorageStatus = "AVAILABLE"
        )

        audit.recordCleanup(candidate, AttachmentCleanupAuditOutcome.VERIFIED_DELETE_INTENT)
        assertEquals(1, audit.countUnclosedDeleteIntents())

        audit.recordCleanup(candidate, AttachmentCleanupAuditOutcome.DELETE_FAILED)
        assertEquals(0, audit.countUnclosedDeleteIntents())
    }

    @Test
    fun `缺失分类只接受显式白名单根内路径`() {
        val configuredRoot = root.resolve("already-removed-root")
        val policy = AttachmentLocalPathPolicy(configuredRoot.toString(), "")

        assertTrue(policy.isAllowedMissingPath(configuredRoot.resolve("jira/1.fbs").toString()))
        assertFalse(policy.isAllowedMissingPath(root.resolve("outside/1.fbs").toString()))
    }
}
