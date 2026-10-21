package com.feedback.security.attachment

import com.feedback.model.attachment.AttachmentContentSource
import com.feedback.model.attachment.SecureAttachmentContent

import com.feedback.service.attachment.*

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import com.feedback.repository.attachment.AttachmentSecurityScanRepository
import com.feedback.common.AttachmentType
import com.feedback.model.po.attachment.AttachmentScanCandidatePo
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import com.feedback.manager.attachment.AttachmentObjectPublisher
import org.slf4j.LoggerFactory

/** 恶意软件扫描封闭结果；不可用必须与清洁结果明确区分。 */
sealed interface AttachmentScanResult {
    /** 扫描器确认内容清洁。 */
    data object Clean : AttachmentScanResult
    /** 扫描器发现恶意签名。 */
    data class Infected(val signature: String) : AttachmentScanResult
    /** 扫描器不可用或返回异常协议。 */
    data class Unavailable(val code: String) : AttachmentScanResult
}

/** 内容审核封闭结果；未知结果不得默认放行。 */
sealed interface AttachmentContentReviewResult {
    /** 内容审核明确通过。 */
    data object Pass : AttachmentContentReviewResult
    /** 内容需要进入人工审核。 */
    data object ReviewNeeded : AttachmentContentReviewResult
    /** 内容审核明确拒绝。 */
    data object Reject : AttachmentContentReviewResult
}

/** 附件内容审核器；实现必须失败关闭，不能把未知类型默认为通过。 */
fun interface AttachmentContentReviewer {
    /** 在事务外审核已通过恶意软件扫描的本地文件。 */
    fun review(path: Path, mimeType: String): AttachmentContentReviewResult
}

/** 默认失败关闭的本地内容审核器；仅显式配置的安全类型可以自动通过。 */
@Component
class FailClosedAttachmentContentReviewer(
    @Value("\${app.attachment.content-review.local-image-pass:false}") private val localImagePass: Boolean,
    @Value("\${app.attachment.content-review.local-video-pass:false}") private val localVideoPass: Boolean,
    @Value("\${app.attachment.content-review.local-log-archive-pass:false}") private val localLogArchivePass: Boolean
) : AttachmentContentReviewer {
    /** 仅显式允许的本地内容类型可通过，归档还必须经过结构安全检查。 */
    override fun review(path: Path, mimeType: String): AttachmentContentReviewResult =
        if (
            mimeType == "text/plain" ||
            (localImagePass && mimeType in setOf("image/jpeg", "image/png", "image/webp")) ||
            (localVideoPass && mimeType in setOf("video/mp4", "video/quicktime", "video/webm")) ||
            (localLogArchivePass && SafeLogArchiveInspector.inspect(path, mimeType))
        ) {
            AttachmentContentReviewResult.Pass
        } else {
            AttachmentContentReviewResult.ReviewNeeded
        }
}

/** 恶意软件扫描器；不可用或协议异常必须返回不可用结果而非放行。 */
fun interface AttachmentSecurityScanner {
    /** 在事务外扫描单个本地文件并返回封闭结果。 */
    fun scan(path: Path): AttachmentScanResult
}

/** ClamAV 流式扫描实现；网络与协议异常统一返回不可用，不得放行附件。 */
@Component
class ClamAvAttachmentSecurityScanner(
    @Value("\${app.attachment.security-scan.enabled:false}") private val enabled: Boolean,
    @Value("\${app.attachment.security-scan.clamav-host:127.0.0.1}") private val host: String,
    @Value("\${app.attachment.security-scan.clamav-port:3310}") private val port: Int,
    @Value("\${app.attachment.security-scan.timeout-ms:5000}") private val timeoutMs: Int,
    private val meters: MeterRegistry
) : AttachmentSecurityScanner {
    /** 按文件 MiB 数给出有上限的响应超时，避免大文件误超时和无界占用线程。 */
    internal fun responseTimeoutMs(fileSizeBytes: Long): Int {
        val base = timeoutMs.coerceIn(500, 30_000).toLong()
        val mib = 1024L * 1024L
        val streamBudget = ((fileSizeBytes.coerceAtLeast(0) + mib - 1) / mib) * 3_000L
        return maxOf(base, streamBudget).coerceAtMost(300_000L).toInt()
    }

    /** 通过 ClamAV INSTREAM 协议流式扫描；网络、协议及禁用状态都失败关闭为 Unavailable。 */
    override fun scan(path: Path): AttachmentScanResult {
        val sample = Timer.start(meters)
        val result = if (!enabled) AttachmentScanResult.Unavailable("ATTACHMENT_SCANNER_DISABLED") else runCatching {
            Socket().use { socket ->
                val connectTimeout = timeoutMs.coerceIn(500, 30_000)
                socket.connect(InetSocketAddress(host, port), connectTimeout)
                socket.soTimeout = responseTimeoutMs(Files.size(path))
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write("zINSTREAM\u0000".toByteArray(Charsets.US_ASCII))
                Files.newInputStream(path).use { raw ->
                    val input = BufferedInputStream(raw)
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(ByteBuffer.allocate(4).putInt(read).array())
                        output.write(buffer, 0, read)
                    }
                    buffer.fill(0)
                }
                output.write(byteArrayOf(0, 0, 0, 0))
                output.flush()
                val response = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty().trimEnd('\u0000')
                when {
                    response.endsWith(" OK") -> AttachmentScanResult.Clean
                    response.endsWith(" FOUND") -> AttachmentScanResult.Infected(response.substringAfter(": ").substringBefore(" FOUND").take(200))
                    else -> AttachmentScanResult.Unavailable("ATTACHMENT_SCANNER_PROTOCOL_ERROR")
                }
            }
        }.getOrElse { AttachmentScanResult.Unavailable("ATTACHMENT_SCANNER_UNAVAILABLE") }
        val outcome = when (result) {
            AttachmentScanResult.Clean -> "clean"
            is AttachmentScanResult.Infected -> "infected"
            is AttachmentScanResult.Unavailable -> "error"
        }
        meters.counter("attachment.security.scan", "outcome", outcome).increment()
        sample.stop(Timer.builder("attachment.security.scan.duration").tag("outcome", outcome).register(meters))
        return result
    }
}

/** 有界附件安全扫描 Worker；文件读取、扫描和审核均在数据库 CAS 之外执行。 */
@Component
class AttachmentSecurityScanWorker(
    private val repository: AttachmentSecurityScanRepository,
    private val scanner: AttachmentSecurityScanner,
    private val contentReviewer: AttachmentContentReviewer,
    private val storageCrypto: PlatformAttachmentStorageCrypto,
    @Value("\${app.attachment.security-scan.batch-size:100}") private val batchSize: Int,
    @Value("\${app.attachment.security-scan.concurrency:8}") configuredConcurrency: Int,
    private val objectPublisher: AttachmentObjectPublisher? = null,
    private val logPreviews: AttachmentLogPreviewService? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val concurrency = configuredConcurrency.coerceIn(1, 8)
    private val threadSequence = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(concurrency) { task ->
        Thread(task, "attachment-security-${threadSequence.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    /** 有界读取候选并以最多八个守护线程并发处理；不存在数据库长事务。 */
    fun scanPending() {
        val rows = repository.pending(batchSize)
        CompletableFuture.allOf(*rows.map { row ->
            CompletableFuture.runAsync({ scanOne(row) }, executor)
        }.toTypedArray()).join()
    }

    /** 在事务外执行扫描、审核、预览、加密和文件删除，最后以单条条件 SQL 提交状态。 */
    private fun scanOne(row: AttachmentScanCandidatePo) {
        val id = row.id
        when (val result = scanner.scan(Path.of(row.storagePath))) {
            AttachmentScanResult.Clean -> when (contentReviewer.review(Path.of(row.storagePath), row.mimeType.toString())) {
                AttachmentContentReviewResult.Pass -> {
                    if (!storageCrypto.available()) {
                        return
                    }
                    val plainPath = Path.of(row.storagePath)
                    val logPreviewService = logPreviews
                    val encryptedPreview = if (
                        AttachmentType.fromStorage(row.attachmentType) == AttachmentType.LOG && logPreviewService != null
                    ) {
                        runCatching {
                            logPreviewService.createEncryptedPreview(
                                SecureAttachmentContent(
                                    AttachmentContentSource { Files.newInputStream(plainPath) },
                                    row.mimeType ?: "application/octet-stream",
                                    row.sourceAttachmentId ?: plainPath.fileName.toString(),
                                    Files.size(plainPath)
                                )
                            )
                        }.onFailure { error ->
                            logger.error(
                                "Initial log preview generation failed attachment_id={} error_code={} error_type={}",
                                id,
                                "ATTACHMENT_LOG_PREVIEW_GENERATION_FAILED",
                                error.javaClass.simpleName
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                    val encryptedPath = plainPath.resolveSibling(plainPath.fileName.toString() + ".fbs").normalize()
                    storageCrypto.encrypt(plainPath, encryptedPath)
                    Files.delete(plainPath)
                    if (repository.verified(
                            id,
                            encryptedPath.toString(),
                            storageCrypto.keyId,
                            encryptedPreview?.ciphertext,
                            encryptedPreview?.plaintextBytes
                        ) == 1
                    ) {
                        runCatching { objectPublisher?.publishNew(id) }.onFailure { error ->
                            // 本机 VERIFIED 内容继续可读；有界迁移 worker 会重试对象发布。
                            logger.error(
                                "Verified attachment object publish failed attachment_id={} error_code={} error_type={}",
                                id,
                                "ATTACHMENT_OBJECT_PUBLISH_FAILED",
                                error.javaClass.simpleName
                            )
                        }
                    }
                }
                AttachmentContentReviewResult.ReviewNeeded -> repository.unsupported(id)
                AttachmentContentReviewResult.Reject -> repository.rejected(id)
            }
            is AttachmentScanResult.Infected -> repository.infected(id)
            is AttachmentScanResult.Unavailable -> repository.unavailable(id, result.code)
        }
    }

    /** 应用关闭时停止接收新任务；正在运行的扫描按线程池语义自然结束。 */
    @PreDestroy
    fun shutdown() {
        executor.shutdown()
    }
}
