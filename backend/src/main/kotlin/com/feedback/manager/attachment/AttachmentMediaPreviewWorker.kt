package com.feedback.manager.attachment

import com.feedback.model.attachment.*

import com.feedback.service.attachment.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.config.AttachmentObjectStorageProperties
import com.feedback.config.AttachmentMediaPreviewProperties
import com.feedback.model.po.attachment.AttachmentMediaPreviewCandidatePo
import com.feedback.model.attachment.AttachmentObjectKey
import com.feedback.repository.attachment.AttachmentPreviewRepository
import com.feedback.model.attachment.StoredAttachmentObject
import com.feedback.repository.remote.attachment.AttachmentObjectStorage
import com.feedback.model.attachment.PutAttachmentObjectCommand
import com.feedback.model.attachment.DeleteAttachmentObjectCommand
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max

/** 将已通过安全门禁的媒体转换为有界 JPEG；不负责授权、持久化或公开地址。 */
interface AttachmentMediaPreviewGenerator {
    /** 判断当前运行环境能否处理该候选类型，不执行文件或进程 I/O。 */
    fun supports(candidate: AttachmentMediaPreviewCandidatePo): Boolean = true

    /** 在事务外生成有尺寸和字节上限的 JPEG 派生预览。 */
    fun generate(candidate: AttachmentMediaPreviewCandidatePo, content: SecureAttachmentContent): GeneratedMediaPreview
}

/** 有界媒体预览生成器；图片内存解码和 FFmpeg 子进程均在数据库事务外执行。 */
@Component
class BoundedAttachmentMediaPreviewGenerator(
    private val settings: AttachmentMediaPreviewProperties
) : AttachmentMediaPreviewGenerator {
    /** 图片总是支持；视频仅在显式启用 FFmpeg 后支持。 */
    override fun supports(candidate: AttachmentMediaPreviewCandidatePo): Boolean =
        (candidate.attachmentType !in VIDEO_TYPES && candidate.mimeType != "image/webp") || settings.ffmpegEnabled

    /** 校验源大小后按候选类型选择图片解码或受控外部进程抽帧。 */
    override fun generate(candidate: AttachmentMediaPreviewCandidatePo, content: SecureAttachmentContent): GeneratedMediaPreview {
        val length = content.contentLength ?: generationFailed("ATTACHMENT_MEDIA_PREVIEW_SOURCE_LENGTH_MISSING")
        if (length !in 1..settings.maxSourceBytes) {
            generationFailed("ATTACHMENT_MEDIA_PREVIEW_SOURCE_TOO_LARGE")
        }
        return if (candidate.attachmentType in VIDEO_TYPES || candidate.mimeType == "image/webp") {
            externalFrame(content, candidate.attachmentType in VIDEO_TYPES)
        } else image(content)
    }

    /** 从有界输入流解码图片并缩放编码，不保留原始尺寸。 */
    private fun image(content: SecureAttachmentContent): GeneratedMediaPreview = content.openStream().use { input ->
        val imageInput = ImageIO.createImageInputStream(input)
            ?: generationFailed("ATTACHMENT_MEDIA_PREVIEW_IMAGE_INPUT_INVALID")
        imageInput.use { source ->
            val reader = ImageIO.getImageReaders(source).asSequence().firstOrNull()
                ?: generationFailed("ATTACHMENT_MEDIA_PREVIEW_IMAGE_UNSUPPORTED")
            try {
                reader.input = source
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                validateDimensions(width, height)
                val sample = max(1, ceil(max(width, height).toDouble() / boundedDimension()).toInt())
                val parameters = reader.defaultReadParam.apply {
                    setSourceSubsampling(sample, sample, 0, 0)
                }
                encode(reader.read(0, parameters))
            } finally {
                reader.dispose()
            }
        }
    }

    /** 视频只允许单进程、单线程、单帧、有界临时输入和硬超时；失败时后台降级，不阻塞页面。 */
    private fun externalFrame(content: SecureAttachmentContent, seekVideo: Boolean): GeneratedMediaPreview {
        if (!settings.ffmpegEnabled) generationFailed("ATTACHMENT_MEDIA_PREVIEW_FFMPEG_DISABLED")
        val input = Files.createTempFile("attachment-preview-source-", ".video")
        val output = Files.createTempFile("attachment-preview-output-", ".jpg")
        try {
            content.openStream().use { source ->
                Files.newOutputStream(input).use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) {
                            break
                        }
                        total += read
                        if (total > settings.maxSourceBytes) generationFailed("ATTACHMENT_MEDIA_PREVIEW_SOURCE_TOO_LARGE")
                        target.write(buffer, 0, read)
                    }
                    buffer.fill(0)
                }
            }
            val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.videoTimeoutMs)
            // 固定 0.5s 的定位对普通录制有效，但会越过极短片段的结尾。
            // 这时改从第一个可解码帧重试，
            // 同时让两个子进程共用同一个硬超时。
            val seekAttempts = if (seekVideo) listOf("0.5", null) else listOf(null)
            for (seek in seekAttempts) {
                Files.deleteIfExists(output)
                val command = mutableListOf(
                    settings.ffmpegPath, "-nostdin", "-hide_banner", "-loglevel", "error", "-threads", "1"
                )
                seek?.let { command += listOf("-ss", it) }
                command += listOf(
                    "-i", input.toString(), "-frames:v", "1",
                    "-vf", "scale=${boundedDimension()}:${boundedDimension()}:force_original_aspect_ratio=decrease",
                    "-q:v", "5", "-y", output.toString()
                )
                val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                if (remainingMillis <= 0) generationFailed("ATTACHMENT_MEDIA_PREVIEW_VIDEO_TIMEOUT")
                val process = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                if (!process.waitFor(remainingMillis, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                    generationFailed("ATTACHMENT_MEDIA_PREVIEW_VIDEO_TIMEOUT")
                }
                if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0L) {
                    continue
                }
                val bytes = Files.readAllBytes(output)
                val decoded = ImageIO.read(ByteArrayInputStream(bytes)) ?: continue
                validateDimensions(decoded.width, decoded.height)
                if (max(decoded.width, decoded.height) > boundedDimension()) {
                    generationFailed("ATTACHMENT_MEDIA_PREVIEW_OUTPUT_DIMENSIONS_INVALID")
                }
                // ffmpeg 已按尺寸设界。对异常噪点多的封面帧，
                // 重新编码是一道安全的最终压缩兜底。
                    if (bytes.size > settings.maxOutputBytes) {
                        return encode(decoded)
                    }
                return GeneratedMediaPreview(bytes, decoded.width, decoded.height)
            }
            generationFailed("ATTACHMENT_MEDIA_PREVIEW_VIDEO_DECODE_FAILED")
        } finally {
            Files.deleteIfExists(input)
            Files.deleteIfExists(output)
        }
    }

    /** 按最大边长等比缩放并编码 JPEG，同时执行输出大小上限校验。 */
    private fun encode(source: BufferedImage): GeneratedMediaPreview {
        val scale = minOf(1.0, boundedDimension().toDouble() / max(source.width, source.height))
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        val output = ByteArrayOutputStream()
        if (!ImageIO.write(target, "jpeg", output)) generationFailed("ATTACHMENT_MEDIA_PREVIEW_JPEG_WRITER_MISSING")
        val bytes = output.toByteArray()
        if (bytes.size !in 1..settings.maxOutputBytes) {
            generationFailed("ATTACHMENT_MEDIA_PREVIEW_OUTPUT_TOO_LARGE")
        }
        return GeneratedMediaPreview(bytes, width, height)
    }

    /** 拒绝非正数或像素总量过大的输入，防止图片解压炸弹。 */
    private fun validateDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || width.toLong() * height.toLong() > settings.maxSourcePixels) {
            generationFailed("ATTACHMENT_MEDIA_PREVIEW_DIMENSIONS_INVALID")
        }
    }

    /** 返回经过配置类边界校验的预览最大边长。 */
    private fun boundedDimension() = settings.maxDimension

    private companion object {
        val VIDEO_TYPES = setOf("VIDEO", "SCREEN_RECORDING")
    }
}

/**
 * 安全扫描完成后的异步媒体派生 worker。CAS 租约保证多实例幂等领取；原件读取、转码和 MinIO I/O
 * 均在数据库事务外，页面请求从不触发同步生成。
 */
@Component
class AttachmentMediaPreviewWorker(
    private val repository: AttachmentPreviewRepository,
    private val provider: AttachmentContentProvider,
    private val generator: AttachmentMediaPreviewGenerator,
    private val storageProvider: ObjectProvider<AttachmentObjectStorage>,
    private val storageProperties: AttachmentObjectStorageProperties,
    private val settings: AttachmentMediaPreviewProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val threads = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(settings.concurrency) { task ->
        Thread(task, "attachment-media-preview-${threads.incrementAndGet()}").apply { isDaemon = true }
    }
    private val leaseHeartbeat: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "attachment-media-preview-lease").apply { isDaemon = true }
    }

    /** 有界领取候选并并发生成；FFmpeg、原件读取和对象上传全部位于事务外。 */
    fun generateBatch() {
        if (!settings.enabled || !storageProperties.enabled) {
            return
        }
        val storage = storageProvider.ifAvailable ?: return
        val tasks = repository.findMediaPreviewCandidates(settings.batchSize, settings.ffmpegEnabled).mapNotNull { candidate ->
            if (!generator.supports(candidate)) {
                return@mapNotNull null
            }
            val leaseOwner = UUID.randomUUID().toString()
            if (!repository.claimMediaPreview(candidate.id, settings.leaseSeconds, leaseOwner)) null else CompletableFuture.runAsync(
                { generateOne(candidate, leaseOwner, storage) }, executor
            )
        }
        CompletableFuture.allOf(*tasks.toTypedArray()).join()
    }

    /** 领取租约后生成、上传、回读校验并以 owner 条件 CAS 完成；失败按上限重试策略落库。 */
    private fun generateOne(candidate: AttachmentMediaPreviewCandidatePo, leaseOwner: String, storage: AttachmentObjectStorage) {
        var uploaded: StoredAttachmentObject? = null
        val heartbeat = leaseHeartbeat.scheduleAtFixedRate(
            {
                runCatching { repository.renewMediaPreviewLease(candidate.id, settings.leaseSeconds, leaseOwner) }
                    .onFailure { logger.warn("Media preview lease renewal failed attachment_id={}", candidate.id) }
            },
            (settings.leaseSeconds / 3).coerceAtLeast(3).toLong(),
            (settings.leaseSeconds / 3).coerceAtLeast(3).toLong(),
            TimeUnit.SECONDS
        )
        runCatching {
            val preview = generator.generate(candidate, provider.open(candidate.id))
            val key = AttachmentObjectKey("derived/previews/v2/${candidate.id}/$leaseOwner.jpg")
            val stored = ByteArrayInputStream(preview.bytes).use { content ->
                storage.put(PutAttachmentObjectCommand(key, content, preview.bytes.size.toLong(), PREVIEW_CONTENT_TYPE))
            }
            uploaded = stored
            val verified = storage.head(key) ?: generationFailed("ATTACHMENT_MEDIA_PREVIEW_OBJECT_MISSING")
            if (verified.contentLength != preview.bytes.size.toLong() || verified.contentType != PREVIEW_CONTENT_TYPE) {
                generationFailed("ATTACHMENT_MEDIA_PREVIEW_OBJECT_MISMATCH")
            }
            if (!repository.storeMediaPreview(
                    candidate.id, key, stored, preview.bytes.size.toLong(), preview.width, preview.height, leaseOwner
                )) generationFailed("ATTACHMENT_MEDIA_PREVIEW_CAS_FAILED")
            uploaded = null
        }.onFailure { error ->
            uploaded?.let { stored ->
                runCatching { storage.delete(DeleteAttachmentObjectCommand(stored.key, stored.version)) }
            }
            repository.markMediaPreviewFailed(candidate.id, stablePreviewError(error), leaseOwner)
            logger.warn(
                "Media preview generation failed attachment_id={} error_code={} error_type={}",
                candidate.id, stablePreviewError(error), error.javaClass.simpleName
            )
        }.also {
            heartbeat.cancel(false)
        }
    }

    /** 关闭 worker 线程池，避免应用停止后继续接收生成任务。 */
    @PreDestroy
    fun shutdown() {
        executor.shutdown()
        leaseHeartbeat.shutdown()
    }
}

private const val PREVIEW_CONTENT_TYPE = "image/jpeg"

/** 将内部异常收敛为长度稳定的预览失败码，避免外部消息直接入库。 */
private fun stablePreviewError(error: Throwable): String =
    (error as? ServiceException)?.reasonCode?.takeIf { it.startsWith("ATTACHMENT_MEDIA_PREVIEW_") }
        ?: "ATTACHMENT_MEDIA_PREVIEW_GENERATION_FAILED"

/** 以附件服务不可用错误终止当前预览生成，不把失败误标为成功。 */
private fun generationFailed(reasonCode: String): Nothing =
    throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, reasonCode)
