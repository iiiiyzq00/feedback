package com.feedback.service.attachment

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import com.feedback.repository.attachment.*
import com.feedback.model.attachment.*
import io.mockk.every
import io.mockk.mockk
import com.feedback.repository.attachment.SecureAttachmentRepository
import com.feedback.repository.attachment.StoredAttachmentContent
import com.feedback.repository.attachment.AttachmentObjectLocationFacts
import com.feedback.repository.attachment.AttachmentOwnerFacts
import com.feedback.config.AttachmentObjectStorageProperties
import com.feedback.model.attachment.AttachmentObjectMetadata
import com.feedback.model.attachment.AttachmentObjectRead
import com.feedback.repository.remote.attachment.AttachmentObjectStorage
import com.feedback.common.exception.ClientException
import com.feedback.common.AttachmentLogPreviewStatus
import com.feedback.common.AttachmentType
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class LocalAttachmentContentProviderTest {
    @TempDir lateinit var root: Path
    private val attachments = mockk<SecureAttachmentRepository>()

    @Test fun `opens only verified regular file below configured root`() {
        val file = root.resolve("station/1/a.jpg"); Files.createDirectories(file.parent); Files.write(file, byteArrayOf(1,2,3))
        every { attachments.findViewableContent(1) } returns StoredAttachmentContent(1, file.toString(), "image/jpeg", AttachmentType.IMAGE, 3L)
        every { attachments.findObjectStorageLocation(1) } returns null
        val crypto = mockk<PlatformAttachmentStorageCrypto>(); every { crypto.open(file) } returns java.io.ByteArrayInputStream(byteArrayOf(1,2,3))
        val content = LocalQuarantineAwareAttachmentContentProvider(attachments, crypto, pathPolicy(), true).open(1)
        assertEquals(3, content.openStream().readAllBytes().size)
        assertEquals(3L, content.contentLength)
    }

    @Test fun `rejects database path outside storage root`() {
        every { attachments.findViewableContent(1) } returns StoredAttachmentContent(1, "/etc/hosts", "text/plain", AttachmentType.LOG, null)
        every { attachments.findObjectStorageLocation(1) } returns null
        val error = assertThrows(ClientException::class.java) {
            LocalQuarantineAwareAttachmentContentProvider(attachments, mockk(), pathPolicy(), true).open(1)
        }
        assertEquals("ATTACHMENT_NOT_AVAILABLE", error.reasonCode)
    }

    @Test fun `opens cached encrypted log preview without opening original storage file`() {
        val ciphertext = byteArrayOf(7, 8, 9)
        val plaintext = "preview line".toByteArray()
        every { attachments.findViewableContent(1) } returns StoredAttachmentContent(
            1, null, "application/gzip", AttachmentType.LOG, 91_000_000L,
            ciphertext, "attachment-storage-v1", plaintext.size, AttachmentLogPreviewStatus.AVAILABLE
        )
        val crypto = mockk<PlatformAttachmentStorageCrypto>()
        every { crypto.keyId } returns "attachment-storage-v1"
        every { crypto.decryptBytes(ciphertext) } returns plaintext

        val content = LocalQuarantineAwareAttachmentContentProvider(attachments, crypto, pathPolicy(), true)
            .openLogPreview(1)

        assertEquals("preview line", content?.openStream()?.bufferedReader()?.readText())
        verify(exactly = 0) { crypto.open(any()) }
    }

    @Test fun `explicit legacy root permits historical file without trusting arbitrary absolute paths`() {
        val current = root.resolve("current")
        val legacy = root.resolve("legacy")
        Files.createDirectories(current)
        Files.createDirectories(legacy)
        val historical = Files.write(legacy.resolve("historical.fbs"), byteArrayOf(1))
        val outside = Files.write(root.resolve("outside.fbs"), byteArrayOf(2))
        val policy = AttachmentLocalPathPolicy(current.toString(), legacy.toString())

        assertEquals(historical, policy.readablePath(historical.toString()))
        assertEquals(null, policy.readablePath(outside.toString()))
    }

    @Test fun `parent symlink cannot escape an allowed root`() {
        val current = Files.createDirectories(root.resolve("current"))
        val outside = Files.createDirectories(root.resolve("outside"))
        val secret = Files.write(outside.resolve("secret.fbs"), byteArrayOf(1))
        val linkedParent = current.resolve("linked")
        runCatching { Files.createSymbolicLink(linkedParent, outside) }.getOrElse { return }

        val policy = AttachmentLocalPathPolicy(current.toString(), "")

        assertEquals(null, policy.readablePath(linkedParent.resolve(secret.fileName).toString()))
    }

    @Test fun `available object is authoritative and local recovery file is not opened`() {
        val local = root.resolve("station/1/a.jpg")
        Files.createDirectories(local.parent)
        Files.write(local, byteArrayOf(9, 9, 9))
        val crypto = mockk<PlatformAttachmentStorageCrypto>()
        val storage = mockk<AttachmentObjectStorage>()
        val provider = mockk<ObjectProvider<AttachmentObjectStorage>>()
        val key = AttachmentObjectKey("attachments/station/1/hash.jpg")
        every { attachments.findViewableContent(1) } returns StoredAttachmentContent(1, local.toString(), "image/jpeg", AttachmentType.IMAGE, 3L)
        every { attachments.findObjectStorageLocation(1) } returns objectLocation(key)
        every { provider.ifAvailable } returns storage
        every { storage.read(key) } returns AttachmentObjectRead(
            AttachmentObjectMetadata(AttachmentStorageBackend.S3_COMPATIBLE, "private-attachments", key, null, null, 3, "image/jpeg", null),
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        )
        val properties = AttachmentObjectStorageProperties().apply { enabled = true; bucket = "private-attachments" }

        val content = LocalQuarantineAwareAttachmentContentProvider(attachments, crypto, pathPolicy(), true, provider, properties).open(1)

        assertEquals(listOf<Byte>(1, 2, 3), content.openStream().readAllBytes().toList())
        verify(exactly = 0) { crypto.open(any()) }
    }

    @Test fun `available object opens after verified local copy cleanup clears storage path`() {
        val crypto = mockk<PlatformAttachmentStorageCrypto>()
        val storage = mockk<AttachmentObjectStorage>()
        val provider = mockk<ObjectProvider<AttachmentObjectStorage>>()
        val key = AttachmentObjectKey("attachments/station/1/hash.jpg")
        every { attachments.findViewableContent(1) } returns StoredAttachmentContent(1, null, "image/jpeg", AttachmentType.IMAGE, 3L)
        every { attachments.findObjectStorageLocation(1) } returns objectLocation(key)
        every { provider.ifAvailable } returns storage
        every { storage.read(key) } returns AttachmentObjectRead(
            AttachmentObjectMetadata(AttachmentStorageBackend.S3_COMPATIBLE, "private-attachments", key, null, null, 3, "image/jpeg", null),
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        )
        val properties = AttachmentObjectStorageProperties().apply { enabled = true; bucket = "private-attachments" }

        val content = LocalQuarantineAwareAttachmentContentProvider(
            attachments, crypto, pathPolicy(), true, provider, properties
        ).open(1)

        assertEquals(listOf<Byte>(1, 2, 3), content.openStream().readAllBytes().toList())
        verify(exactly = 0) { crypto.open(any()) }
    }

    @Test fun `media preview opens only its recorded private derived object`() {
        val crypto = mockk<PlatformAttachmentStorageCrypto>()
        val storage = mockk<AttachmentObjectStorage>()
        val provider = mockk<ObjectProvider<AttachmentObjectStorage>>()
        val key = AttachmentObjectKey("derived/previews/v1/1/poster.jpg")
        every { attachments.findViewableContent(1) } returns StoredAttachmentContent(1, null, "image/png", AttachmentType.IMAGE, 90_000L)
        every { attachments.findMediaPreviewLocation(1) } returns AttachmentMediaPreviewLocationFacts(
            key, null, "etag", 3, 88, 50
        )
        every { provider.ifAvailable } returns storage
        every { storage.read(key) } returns AttachmentObjectRead(
            AttachmentObjectMetadata(AttachmentStorageBackend.S3_COMPATIBLE, "private-attachments", key, null, "etag", 3, "image/jpeg", null),
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        )
        val properties = AttachmentObjectStorageProperties().apply { enabled = true; bucket = "private-attachments" }

        val content = LocalQuarantineAwareAttachmentContentProvider(
            attachments, crypto, pathPolicy(), true, provider, properties
        ).openMediaPreview(1)

        assertEquals("image/jpeg", content?.mimeType)
        assertEquals(listOf<Byte>(1, 2, 3), content?.openStream()?.readAllBytes()?.toList())
        verify(exactly = 0) { attachments.findObjectStorageLocation(any()) }
        verify(exactly = 0) { crypto.open(any()) }
    }

    @Test fun `available object failure never falls back to retained local file`() {
        val local = root.resolve("station/1/a.jpg")
        Files.createDirectories(local.parent)
        Files.write(local, byteArrayOf(9, 9, 9))
        val crypto = mockk<PlatformAttachmentStorageCrypto>()
        val storage = mockk<AttachmentObjectStorage>()
        val provider = mockk<ObjectProvider<AttachmentObjectStorage>>()
        val key = AttachmentObjectKey("attachments/station/1/hash.jpg")
        every { attachments.findViewableContent(1) } returns StoredAttachmentContent(1, local.toString(), "image/jpeg", AttachmentType.IMAGE, 3L)
        every { attachments.findObjectStorageLocation(1) } returns objectLocation(key)
        every { provider.ifAvailable } returns storage
        every { storage.read(key) } throws IllegalStateException("minio unavailable")
        val properties = AttachmentObjectStorageProperties().apply { enabled = true; bucket = "private-attachments" }

        assertThrows(IllegalStateException::class.java) {
            LocalQuarantineAwareAttachmentContentProvider(attachments, crypto, pathPolicy(), true, provider, properties).open(1)
        }
        verify(exactly = 0) { crypto.open(any()) }
    }

    private fun objectLocation(key: AttachmentObjectKey) = AttachmentObjectLocationFacts(
        id = 1,
        owner = AttachmentOwnerFacts(1, null),
        backend = AttachmentStorageBackend.S3_COMPATIBLE,
        bucket = "private-attachments",
        objectKey = key,
        version = null,
        etag = "etag",
        lifecycleStatus = AttachmentObjectLifecycleStatus.AVAILABLE,
        migratedAt = null
    )

    private fun pathPolicy(legacyRoots: String = "") = AttachmentLocalPathPolicy(root.toString(), legacyRoots)
}
