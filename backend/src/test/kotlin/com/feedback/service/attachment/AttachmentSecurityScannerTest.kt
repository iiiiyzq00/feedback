package com.feedback.service.attachment

import com.feedback.model.attachment.*

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.assertEquals
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.feedback.repository.attachment.AttachmentSecurityScanRepository
import com.feedback.model.po.attachment.AttachmentScanCandidatePo
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AttachmentSecurityScannerTest {
    @Test fun `plain text logs pass content gate only after malware scanner calls reviewer`() {
        val reviewer=FailClosedAttachmentContentReviewer(false,false,false)
        assertEquals(AttachmentContentReviewResult.Pass,reviewer.review(Path.of("unused.log"),"text/plain"))
        assertEquals(AttachmentContentReviewResult.ReviewNeeded,reviewer.review(Path.of("unused.gz"),"application/gzip"))
    }

    @Test fun `safe zip and gzip logs pass while traversal and compression bombs fail`() {
        val reviewer=FailClosedAttachmentContentReviewer(false,false,true)
        val zip=root.resolve("safe.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use{out->out.putNextEntry(ZipEntry("logs/app.log"));out.write("safe log line".toByteArray());out.closeEntry()}
        assertEquals(AttachmentContentReviewResult.Pass,reviewer.review(zip,"application/zip"))
        val traversal=root.resolve("traversal.zip")
        ZipOutputStream(Files.newOutputStream(traversal)).use{out->out.putNextEntry(ZipEntry("../escape.log"));out.write("unsafe".toByteArray());out.closeEntry()}
        assertEquals(AttachmentContentReviewResult.ReviewNeeded,reviewer.review(traversal,"application/zip"))
        val gzip=root.resolve("safe.gz")
        GZIPOutputStream(Files.newOutputStream(gzip)).use{it.write("safe log line".toByteArray())}
        assertEquals(AttachmentContentReviewResult.Pass,reviewer.review(gzip,"application/gzip"))
        val bomb=root.resolve("bomb.gz")
        GZIPOutputStream(Files.newOutputStream(bomb)).use{out->repeat(1024){out.write(ByteArray(8192))}}
        assertEquals(AttachmentContentReviewResult.ReviewNeeded,reviewer.review(bomb,"application/gzip"))
    }
    @TempDir lateinit var root: Path
    private val repository = mockk<AttachmentSecurityScanRepository>(relaxed = true)

    @Test
    fun `clean station attachment is released from quarantine`() {
        val file = root.resolve("a.jpg"); Files.write(file, byteArrayOf(1, 2, 3))
        every { repository.pending(20) } returns listOf(AttachmentScanCandidatePo(7,file.toString(),"image/jpeg"))
        val storage = mockk<PlatformAttachmentStorageCrypto>(relaxed = true) {
            every { available() } returns true; every { keyId } returns "test"
            every { encrypt(file, any()) } answers { Files.copy(file, secondArg<Path>()); Unit }
        }
        AttachmentSecurityScanWorker(repository, AttachmentSecurityScanner { AttachmentScanResult.Clean },
            AttachmentContentReviewer { _, _ -> AttachmentContentReviewResult.Pass }, storage, 20, 1).scanPending()
        verify { repository.verified(7,any(),"test") }
    }

    @Test
    fun `unavailable scanner fails closed without changing status`() {
        every { repository.pending(20) } returns listOf(AttachmentScanCandidatePo(8,"/tmp/a.jpg","image/jpeg"))
        AttachmentSecurityScanWorker(repository, AttachmentSecurityScanner { AttachmentScanResult.Unavailable("DOWN") },
            AttachmentContentReviewer { _, _ -> AttachmentContentReviewResult.Pass }, mockk(relaxed = true), 20, 1).scanPending()
        verify { repository.unavailable(8,"DOWN") }
    }

    @Test
    fun `unsupported clean evidence is deterministically rejected without manual queue`() {
        val file = root.resolve("unsupported.tar"); Files.write(file, byteArrayOf(1, 2, 3))
        every { repository.pending(20) } returns listOf(AttachmentScanCandidatePo(9,file.toString(),"application/x-tar"))
        AttachmentSecurityScanWorker(repository, AttachmentSecurityScanner { AttachmentScanResult.Clean },
            AttachmentContentReviewer { _, _ -> AttachmentContentReviewResult.ReviewNeeded }, mockk(relaxed = true), 20, 1).scanPending()
        verify { repository.unsupported(9) }
    }

    @Test
    fun `large evidence receives size aware ClamAV response timeout`() {
        val scanner=ClamAvAttachmentSecurityScanner(true,"127.0.0.1",3310,5_000,SimpleMeterRegistry())
        assertEquals(246_000,scanner.responseTimeoutMs(82L*1024L*1024L))
        assertEquals(5_000,scanner.responseTimeoutMs(1024L))
    }
}
