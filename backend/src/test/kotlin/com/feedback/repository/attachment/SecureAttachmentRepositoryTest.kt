package com.feedback.repository.attachment

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.attachment.FeedbackAttachmentMapper
import com.feedback.model.po.attachment.FeedbackAttachmentListPo
import com.feedback.model.po.attachment.FeedbackAttachmentPo
import com.feedback.repository.impl.attachment.MyBatisSecureAttachmentRepositoryImpl
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import com.feedback.common.AttachmentType

class SecureAttachmentRepositoryTest {
    private val mapper = mockk<FeedbackAttachmentMapper>()
    private val repository = MyBatisSecureAttachmentRepositoryImpl(mapper)

    @Test
    fun `list mapping preserves mapper order and exposes no storage path`() {
        val queryTime = LocalDateTime.of(2026, 8, 29, 16, 30)
        every { mapper.findNonLogListBySourceId(11) } returns listOf(
            listPo(7, "SCREENSHOT", queryTime),
            listPo(8, "VIDEO", queryTime)
        )

        val actual = repository.findNonLogListFacts(11)

        assertEquals(listOf(7L, 8L), actual.map { it.id })
        assertEquals(listOf(AttachmentType.SCREENSHOT, AttachmentType.VIDEO), actual.map { it.attachmentType })
        assertEquals(queryTime, actual.first().queryTime)
        assertEquals(true, actual.first().storedContentPresent)
    }

    @Test
    fun `evidence list includes log metadata without exposing storage path`() {
        val queryTime = LocalDateTime.of(2026, 9, 3, 16, 0)
        every { mapper.findListBySourceId(11) } returns listOf(
            listPo(7, "SCREENSHOT", queryTime),
            listPo(9, "LOG", queryTime)
        )

        val actual = repository.findListFacts(11)

        assertEquals(listOf(AttachmentType.SCREENSHOT, AttachmentType.LOG), actual.map { it.attachmentType })
        assertEquals(listOf(7L, 9L), actual.map { it.id })
    }

    @Test
    fun `batch authorization facts use one bounded mapper query`() {
        every { mapper.findRetainedByIdsAndSourceId(listOf(7L, 8L), 11) } returns listOf(
            FeedbackAttachmentPo(
                id = 7,
                sourceId = 11,
                attachmentType = "IMAGE",
                securityStatus = "VERIFIED",
                integrityStatus = "VERIFIED",
                decryptionStatus = "NOT_REQUIRED",
                malwareScanStatus = "CLEAN",
                contentReviewStatus = "PASS"
            ),
            FeedbackAttachmentPo(
                id = 8,
                sourceId = 11,
                attachmentType = "VIDEO",
                securityStatus = "VERIFIED",
                integrityStatus = "VERIFIED",
                decryptionStatus = "NOT_REQUIRED",
                malwareScanStatus = "CLEAN",
                contentReviewStatus = "PASS"
            )
        )

        val actual = repository.findAuthorizationFacts(listOf(7L, 8L), 11)

        assertEquals(listOf(7L, 8L), actual.map { it.id })
        assertEquals(listOf(AttachmentType.IMAGE, AttachmentType.VIDEO), actual.map { it.attachmentType })
    }

    @Test
    fun `list mapping fails closed when database query time is missing`() {
        val queryTime = LocalDateTime.of(2026, 8, 29, 16, 30)
        every { mapper.findNonLogListBySourceId(11) } returns listOf(
            listPo(7, "SCREENSHOT", queryTime).copy(queryTime = null)
        )

        val error = assertThrows(ServiceException::class.java) {
            repository.findNonLogListFacts(11)
        }

        assertEquals(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, error.errorCode)
        assertEquals("ATTACHMENT_LIST_QUERY_TIME_MISSING", error.reasonCode)
    }

    @Test
    fun `object location mapping returns named private storage facts without legacy path`() {
        every { mapper.findObjectStorageLocationById(7) } returns FeedbackAttachmentPo(
            id = 7,
            sourceId = 11,
            attachmentType = "SCREENSHOT",
            storageBackend = "S3_COMPATIBLE",
            objectBucket = "private-attachments",
            objectKey = "feedback/11/7.png",
            objectVersion = "v2",
            objectEtag = "etag",
            objectStorageStatus = "AVAILABLE",
            objectMigratedAt = LocalDateTime.of(2026, 9, 1, 9, 30)
        )

        val actual = repository.findObjectStorageLocation(7)

        assertEquals("private-attachments", actual?.bucket)
        assertEquals("feedback/11/7.png", actual?.objectKey?.value)
        assertEquals("AVAILABLE", actual?.lifecycleStatus?.name)
        assertEquals(11L, actual?.owner?.sourceId)
        assertEquals(null, actual?.owner?.sdkIngestId)
    }

    @Test
    fun `viewable migrated content remains readable after local path is cleared`() {
        every { mapper.findViewableContentById(7) } returns FeedbackAttachmentPo(
            id = 7,
            sourceId = 11,
            attachmentType = "SCREENSHOT",
            mimeType = "image/png",
            fileSizeBytes = 2048,
            storagePath = null,
            storageBackend = "S3_COMPATIBLE",
            objectStorageStatus = "AVAILABLE"
        )

        val actual = repository.findViewableContent(7)

        assertEquals(7, actual?.id)
        assertEquals(null, actual?.storagePath)
        assertEquals("image/png", actual?.mimeType)
        assertEquals(2048, actual?.fileSizeBytes)
    }

    @Test
    fun `SDK-only object location preserves SDK owner without inventing source`() {
        every { mapper.findObjectStorageLocationById(7) } returns objectLocationPo(
            sourceId = null,
            sdkIngestId = 91
        )

        val actual = repository.findObjectStorageLocation(7)

        assertEquals(null, actual?.owner?.sourceId)
        assertEquals(91L, actual?.owner?.sdkIngestId)
    }

    @Test
    fun `object location with no owner fails closed`() {
        every { mapper.findObjectStorageLocationById(7) } returns objectLocationPo(
            sourceId = null,
            sdkIngestId = null
        )

        val error = assertThrows(ServiceException::class.java) {
            repository.findObjectStorageLocation(7)
        }

        assertEquals(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, error.errorCode)
        assertEquals("ATTACHMENT_OWNER_MISSING", error.reasonCode)
    }

    @Test
    fun `object location rejects zero source sentinel`() {
        every { mapper.findObjectStorageLocationById(7) } returns objectLocationPo(
            sourceId = 0,
            sdkIngestId = null
        )

        val error = assertThrows(ServiceException::class.java) {
            repository.findObjectStorageLocation(7)
        }

        assertEquals(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, error.errorCode)
        assertEquals("ATTACHMENT_SOURCE_ID_INVALID", error.reasonCode)
    }

    @Test
    fun `source-scoped authorization fails closed if mapper row loses source`() {
        every { mapper.findRetainedByIdAndSourceId(7, 11) } returns FeedbackAttachmentPo(
            id = 7,
            sourceId = null,
            sdkIngestId = 91,
            attachmentType = "SCREENSHOT"
        )

        val error = assertThrows(ServiceException::class.java) {
            repository.findAuthorizationFacts(7, 11)
        }

        assertEquals(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, error.errorCode)
        assertEquals("ATTACHMENT_SOURCE_ID_MISSING", error.reasonCode)
    }

    @Test
    fun `partial object location fails closed instead of falling back to local path`() {
        every { mapper.findObjectStorageLocationById(7) } returns FeedbackAttachmentPo(
            id = 7,
            sourceId = 11,
            attachmentType = "SCREENSHOT",
            storageBackend = "S3_COMPATIBLE",
            objectBucket = "private-attachments",
            objectKey = null,
            objectStorageStatus = "AVAILABLE"
        )

        val error = assertThrows(ServiceException::class.java) {
            repository.findObjectStorageLocation(7)
        }

        assertEquals(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, error.errorCode)
        assertEquals("ATTACHMENT_OBJECT_KEY_MISSING", error.reasonCode)
    }

    private fun objectLocationPo(sourceId: Long?, sdkIngestId: Long?) = FeedbackAttachmentPo(
        id = 7,
        sourceId = sourceId,
        sdkIngestId = sdkIngestId,
        attachmentType = "SCREENSHOT",
        storageBackend = "S3_COMPATIBLE",
        objectBucket = "private-attachments",
        objectKey = "feedback/11/7.png",
        objectStorageStatus = "AVAILABLE"
    )

    private fun listPo(id: Long, type: String, queryTime: LocalDateTime) = FeedbackAttachmentListPo(
        id = id,
        sourceId = 11,
        attachmentType = type,
        mimeType = "image/png",
        fileSizeBytes = 2048,
        width = 1280,
        height = 720,
        durationSeconds = null,
        uploadStatus = "VERIFIED",
        securityStatus = "VERIFIED",
        integrityStatus = "VERIFIED",
        decryptionStatus = "DECRYPTED",
        malwareScanStatus = "CLEAN",
        contentReviewStatus = "PASS",
        sourceKind = "JIRA",
        retentionUntil = queryTime.plusDays(1),
        createdAt = queryTime.minusMinutes(1),
        deletedAt = null,
        storedContentPresent = true,
        queryTime = queryTime
    )
}
