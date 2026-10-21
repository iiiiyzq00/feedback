package com.feedback.service.attachment

import com.feedback.model.attachment.*

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import com.feedback.model.po.attachment.AttachmentMediaPreviewCandidatePo
import com.feedback.config.AttachmentObjectStorageProperties
import com.feedback.config.AttachmentMediaPreviewProperties
import com.feedback.model.attachment.AttachmentObjectKey
import com.feedback.model.attachment.AttachmentStorageBackend
import com.feedback.repository.attachment.AttachmentPreviewRepository
import com.feedback.model.attachment.StoredAttachmentObject
import com.feedback.model.attachment.AttachmentObjectMetadata
import com.feedback.repository.remote.attachment.AttachmentObjectStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.just
import io.mockk.Runs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.springframework.beans.factory.ObjectProvider

class AttachmentMediaPreviewServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `image preview is a bounded jpeg without retaining the original dimensions`() {
        val source = BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB)
        val encoded = ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()
        val generator = generator()

        val result = generator.generate(
            AttachmentMediaPreviewCandidatePo(22, "IMAGE", "image/png", encoded.size.toLong(), 0),
            SecureAttachmentContent(AttachmentContentSource { encoded.inputStream() }, "image/png", "source.png", encoded.size.toLong())
        )

        assertTrue(result.width <= 480)
        assertEquals(16.0 / 9.0, result.width.toDouble() / result.height, 0.02)
        assertTrue(result.bytes.size < encoded.size)
        assertEquals("ffd8", result.bytes.take(2).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `video remains pending when ffmpeg is not explicitly enabled`() {
        val generator = generator(ffmpegEnabled = false)
        assertFalse(generator.supports(AttachmentMediaPreviewCandidatePo(23, "VIDEO", "video/mp4", 1024, 0)))
        assertFalse(generator.supports(AttachmentMediaPreviewCandidatePo(24, "IMAGE", "image/webp", 1024, 0)))
        assertTrue(generator.supports(AttachmentMediaPreviewCandidatePo(22, "IMAGE", "image/png", 1024, 0)))
    }

    @Test
    fun `short video retries from first frame when half second seek produces no frame`() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")))
        val fixture = tempDir.resolve("fixture.jpg")
        val image = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)
        Files.newOutputStream(fixture).use { ImageIO.write(image, "jpeg", it) }
        val fakeFfmpeg = tempDir.resolve("ffmpeg")
        Files.writeString(
            fakeFfmpeg,
            """#!/bin/sh
                output=''
                for argument in "${'$'}@"; do
                  [ "${'$'}{argument}" = '-ss' ] && exit 9
                  output="${'$'}{argument}"
                done
                cp '${fixture.toString().replace("'", "'\\''")}' "${'$'}{output}"
            """.trimIndent()
        )
        assertTrue(fakeFfmpeg.toFile().setExecutable(true))
        val settings = settings(ffmpegEnabled = true).apply { ffmpegPath = fakeFfmpeg.toString() }
        val source = byteArrayOf(1, 2, 3)

        val result = BoundedAttachmentMediaPreviewGenerator(settings).generate(
            AttachmentMediaPreviewCandidatePo(23, "VIDEO", "video/mp4", source.size.toLong(), 0),
            SecureAttachmentContent(AttachmentContentSource { source.inputStream() }, "video/mp4", "short.mp4", source.size.toLong())
        )

        assertEquals(120, result.width)
        assertEquals(80, result.height)
        assertEquals("ffd8", result.bytes.take(2).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `worker publishes a deterministic private object only after a successful lease claim`() {
        val repository = mockk<AttachmentPreviewRepository>()
        val provider = mockk<AttachmentContentProvider>()
        val generator = mockk<AttachmentMediaPreviewGenerator>()
        val storage = mockk<AttachmentObjectStorage>()
        val storageProvider = mockk<ObjectProvider<AttachmentObjectStorage>>()
        val candidate = AttachmentMediaPreviewCandidatePo(22, "IMAGE", "image/png", 2_048, 0)
        val preview = GeneratedMediaPreview(byteArrayOf(1, 2, 3), 88, 50)
        every { repository.findMediaPreviewCandidates(4, false) } returns listOf(candidate)
        every { generator.supports(candidate) } returns true
        every { repository.claimMediaPreview(22, 60, any()) } returns true
        every { provider.open(22) } returns SecureAttachmentContent(
            AttachmentContentSource { byteArrayOf(7).inputStream() }, "image/png", "source.png", 2_048
        )
        every { generator.generate(candidate, any()) } returns preview
        every { storageProvider.ifAvailable } returns storage
        val storedKey = AttachmentObjectKey("derived/previews/v2/22/test.jpg")
        every { storage.put(any()) } returns
            StoredAttachmentObject(AttachmentStorageBackend.S3_COMPATIBLE, "private", storedKey, null, "etag")
        every { storage.head(any()) } returns AttachmentObjectMetadata(
            AttachmentStorageBackend.S3_COMPATIBLE, "private", storedKey, null, "etag", 3, "image/jpeg", null
        )
        every { repository.storeMediaPreview(22, any(), any(), 3, 88, 50, any()) } returns true
        every { repository.markMediaPreviewFailed(22, any(), any()) } returns true
        every { storage.delete(any()) } just Runs
        val properties = AttachmentObjectStorageProperties().apply { enabled = true }
        val worker = AttachmentMediaPreviewWorker(repository, provider, generator, storageProvider, properties, settings())

        try {
            worker.generateBatch()
        } finally {
            worker.shutdown()
        }

        verify(exactly = 1) { repository.claimMediaPreview(22, 60, any()) }
        verify(exactly = 1) { storage.put(match { it.key.value.startsWith("derived/previews/v2/22/") && it.contentLength == 3L }) }
        verify(exactly = 1) { repository.storeMediaPreview(22, any(), any(), 3, 88, 50, any()) }
    }

    @Test
    fun `worker leaves unsupported video pending without claiming or reading its original`() {
        val repository = mockk<AttachmentPreviewRepository>()
        val provider = mockk<AttachmentContentProvider>()
        val generator = mockk<AttachmentMediaPreviewGenerator>()
        val storage = mockk<AttachmentObjectStorage>()
        val storageProvider = mockk<ObjectProvider<AttachmentObjectStorage>>()
        val candidate = AttachmentMediaPreviewCandidatePo(23, "VIDEO", "video/mp4", 2_048, 0)
        every { repository.findMediaPreviewCandidates(4, false) } returns listOf(candidate)
        every { generator.supports(candidate) } returns false
        every { storageProvider.ifAvailable } returns storage
        val worker = AttachmentMediaPreviewWorker(
            repository, provider, generator, storageProvider,
            AttachmentObjectStorageProperties().apply { enabled = true }, settings()
        )

        try {
            worker.generateBatch()
        } finally {
            worker.shutdown()
        }

        verify(exactly = 0) { repository.claimMediaPreview(any(), any(), any()) }
        verify(exactly = 0) { provider.open(any()) }
        verify(exactly = 0) { storage.put(any()) }
    }

    private fun generator(ffmpegEnabled: Boolean = false) =
        BoundedAttachmentMediaPreviewGenerator(settings(ffmpegEnabled))

    private fun settings(ffmpegEnabled: Boolean = false) = AttachmentMediaPreviewProperties().apply {
        maxSourceBytes = 16L * 1024 * 1024
        maxSourcePixels = 10_000_000
        this.ffmpegEnabled = ffmpegEnabled
    }
}
