package com.feedback.service.attachment

import com.feedback.model.attachment.*

import com.feedback.dto.attachment.FeedbackAttachmentSummaryResponse

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import com.feedback.application.port.feedback.attachment.AttachmentListFacts
import com.feedback.common.*
import com.feedback.model.auth.DataFieldPolicy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class FeedbackAttachmentSummaryResponseTest {
    private val queryTime = LocalDateTime.of(2026, 8, 29, 16, 30)
    private val fieldPolicy = DataFieldPolicy(true, emptySet(), emptySet())

    @Test
    fun `available requires every attachment safety gate`() {
        val available = facts()
        assertTrue(FeedbackAttachmentSummaryResponse.from(available, fieldPolicy).available)
        assertTrue(FeedbackAttachmentSummaryResponse.from(available.copy(retentionUntil = null), fieldPolicy).available)
        assertTrue(
            FeedbackAttachmentSummaryResponse.from(
                available.copy(decryptionStatus = AttachmentDecryptionStatus.NOT_REQUIRED),
                fieldPolicy
            ).available
        )

        val unavailable = listOf(
            available.copy(deletedAt = queryTime),
            available.copy(storedContentPresent = false),
            available.copy(securityStatus = AttachmentSecurityStatus.QUARANTINED),
            available.copy(integrityStatus = AttachmentIntegrityStatus.PENDING),
            available.copy(decryptionStatus = AttachmentDecryptionStatus.FAILED),
            available.copy(malwareScanStatus = AttachmentMalwareScanStatus.PENDING),
            available.copy(contentReviewStatus = AttachmentContentReviewStatus.PENDING),
            available.copy(retentionUntil = queryTime),
            available.copy(retentionUntil = queryTime.minusNanos(1))
        )

        unavailable.forEach { facts ->
            assertFalse(FeedbackAttachmentSummaryResponse.from(facts, fieldPolicy).available, facts.toString())
        }
    }

    private fun facts() = AttachmentListFacts(
        id = 22,
        sourceId = 11,
        attachmentType = AttachmentType.SCREENSHOT,
        mimeType = "image/png",
        fileSizeBytes = 2048,
        width = 1280,
        height = 720,
        durationSeconds = null,
        uploadStatus = AttachmentUploadStatus.VERIFIED,
        securityStatus = AttachmentSecurityStatus.VERIFIED,
        integrityStatus = AttachmentIntegrityStatus.VERIFIED,
        decryptionStatus = AttachmentDecryptionStatus.DECRYPTED,
        malwareScanStatus = AttachmentMalwareScanStatus.CLEAN,
        contentReviewStatus = AttachmentContentReviewStatus.PASS,
        sourceKind = AttachmentSourceKind.JIRA,
        retentionUntil = queryTime.plusNanos(1),
        createdAt = queryTime.minusMinutes(1),
        deletedAt = null,
        storedContentPresent = true,
        queryTime = queryTime
    )
}
