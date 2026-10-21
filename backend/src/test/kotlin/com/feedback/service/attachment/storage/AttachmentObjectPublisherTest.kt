package com.feedback.service.attachment.storage

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import com.feedback.repository.attachment.*
import com.feedback.model.attachment.*
import com.feedback.config.AttachmentObjectStorageProperties
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.attachment.AttachmentObjectMigrationCandidatePo
import com.feedback.security.attachment.PlatformAttachmentStorageCrypto
import com.feedback.security.attachment.AttachmentLocalPathPolicy
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class AttachmentObjectPublisherTest {
    @TempDir lateinit var root: Path

    @Test
    fun `publishes decrypted bytes verifies full read-back and registers object last`() {
        val fixture = fixture("verified attachment".toByteArray())
        var uploadedBytes = byteArrayOf()
        every { fixture.storage.put(any()) } answers {
            val command = firstArg<PutAttachmentObjectCommand>()
            uploadedBytes = command.content.readAllBytes()
            stored(command.key)
        }
        every { fixture.storage.read(any()) } answers {
            val key = AttachmentObjectKey(firstArg<String>())
            objectRead(key, uploadedBytes)
        }
        every {
            fixture.repository.markObjectAvailable(
                fixture.candidate,
                any<com.feedback.model.attachment.StoredAttachmentObject>()
            )
        } returns true

        assertEquals(AttachmentObjectPublishResult.PUBLISHED, fixture.publisher.publish(fixture.candidate))
        assertArrayEquals(fixture.plaintext, uploadedBytes)
        verify(exactly = 1) { fixture.repository.markObjectAvailable(fixture.candidate, any()) }
        verify(exactly = 0) { fixture.storage.delete(any()) }
    }

    @Test
    fun `read-back hash mismatch never registers and compensates uploaded object`() {
        val fixture = fixture("verified attachment".toByteArray())
        every { fixture.storage.put(any()) } answers { stored(firstArg<PutAttachmentObjectCommand>().key) }
        every { fixture.storage.read(any()) } answers {
            objectRead(AttachmentObjectKey(firstArg<String>()), "tampered attachment".toByteArray())
        }
        every { fixture.attachmentReads.findObjectStorageLocation(fixture.candidate.id) } returns null
        every { fixture.storage.delete(any()) } just Runs

        val error = assertThrows(ServiceException::class.java) {
            fixture.publisher.publish(fixture.candidate)
        }

        assertEquals("ATTACHMENT_OBJECT_HASH_MISMATCH", error.reasonCode)
        verify(exactly = 0) { fixture.repository.markObjectAvailable(any(), any()) }
        verify(exactly = 1) { fixture.storage.delete(any()) }
    }

    @Test
    fun `白名单根内缺失源文件返回可审计的专用失败`() {
        val plaintext = "missing attachment".toByteArray()
        val repository = mockk<AttachmentObjectLifecycleRepository>()
        val attachmentReads = mockk<SecureAttachmentRepository>()
        val storage = mockk<AttachmentObjectStorage>()
        val provider = mockk<ObjectProvider<AttachmentObjectStorage>>()
        every { provider.ifAvailable } returns storage
        val properties = AttachmentObjectStorageProperties().apply {
            enabled = true
            bucket = "private-attachments"
        }
        val candidate = AttachmentObjectMigrationCandidatePo(
            id = 31,
            sourceKind = "SDK",
            storagePath = root.resolve("missing.fbs").toString(),
            mimeType = "text/plain",
            fileSizeBytes = plaintext.size.toLong(),
            fileSha256 = MessageDigest.getInstance("SHA-256").digest(plaintext)
        )
        val publisher = AttachmentObjectPublisher(
            repository,
            attachmentReads,
            provider,
            PlatformAttachmentStorageCrypto("a".repeat(32), "attachment-storage-v1"),
            properties,
            AttachmentLocalPathPolicy(root.toString(), "")
        )

        val error = assertThrows(ServiceException::class.java) {
            publisher.publish(candidate)
        }

        assertEquals("ATTACHMENT_LOCAL_SOURCE_UNAVAILABLE", error.reasonCode)
        verify(exactly = 0) { storage.put(any()) }
    }

    @Test
    fun `dry-run migration only reports candidates and performs no upload`() {
        val fixture = fixture("verified attachment".toByteArray(), dryRun = true)
        every { fixture.repository.findObjectMigrationCandidates(0, 25) } returns listOf(fixture.candidate)
        val cleanup = mockk<AttachmentLocalCopyCleanup>()
        val audit = mockk<AttachmentMigrationAudit>()
        val worker = AttachmentObjectMigrationWorker(
            fixture.repository,
            fixture.publisher,
            fixture.properties,
            cleanup,
            audit
        )

        worker.migrateBatch()

        verify(exactly = 1) { fixture.repository.findObjectMigrationCandidates(0, 25) }
        verify(exactly = 0) { fixture.storage.put(any()) }
        verify(exactly = 0) { fixture.repository.markObjectAvailable(any(), any()) }
    }

    private fun fixture(plaintext: ByteArray, dryRun: Boolean = false): Fixture {
        val raw = root.resolve("raw-${plaintext.size}.bin")
        val encrypted = root.resolve("stored-${plaintext.size}.fbs")
        Files.write(raw, plaintext)
        val crypto = PlatformAttachmentStorageCrypto("a".repeat(32), "attachment-storage-v1")
        crypto.encrypt(raw, encrypted)
        val repository = mockk<AttachmentObjectLifecycleRepository>()
        val attachmentReads = mockk<SecureAttachmentRepository>()
        val storage = mockk<AttachmentObjectStorage>()
        val provider = mockk<ObjectProvider<AttachmentObjectStorage>>()
        every { provider.ifAvailable } returns storage
        val properties = AttachmentObjectStorageProperties().apply {
            enabled = true
            bucket = "private-attachments"
            migrationEnabled = true
            migrationDryRun = dryRun
        }
        val candidate = AttachmentObjectMigrationCandidatePo(
            id = plaintext.size.toLong(),
            sourceKind = "SDK",
            storagePath = encrypted.toString(),
            mimeType = "text/plain",
            fileSizeBytes = plaintext.size.toLong(),
            fileSha256 = MessageDigest.getInstance("SHA-256").digest(plaintext)
        )
        return Fixture(
            plaintext,
            repository,
            attachmentReads,
            storage,
            properties,
            candidate,
            AttachmentObjectPublisher(
                repository,
                attachmentReads,
                provider,
                crypto,
                properties,
                AttachmentLocalPathPolicy(root.toString(), "")
            )
        )
    }

    private fun stored(key: AttachmentObjectKey) = StoredAttachmentObject(
        AttachmentStorageBackend.S3_COMPATIBLE,
        "private-attachments",
        key,
        "v1",
        "etag"
    )

    private fun objectRead(key: AttachmentObjectKey, bytes: ByteArray) = AttachmentObjectRead(
        AttachmentObjectMetadata(
            AttachmentStorageBackend.S3_COMPATIBLE,
            "private-attachments",
            key,
            "v1",
            "etag",
            bytes.size.toLong(),
            "text/plain",
            null
        ),
        ByteArrayInputStream(bytes)
    )

    private data class Fixture(
        val plaintext: ByteArray,
        val repository: AttachmentObjectLifecycleRepository,
        val attachmentReads: SecureAttachmentRepository,
        val storage: AttachmentObjectStorage,
        val properties: AttachmentObjectStorageProperties,
        val candidate: AttachmentObjectMigrationCandidatePo,
        val publisher: AttachmentObjectPublisher
    )
}
