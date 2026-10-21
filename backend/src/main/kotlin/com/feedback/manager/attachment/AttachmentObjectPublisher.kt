package com.feedback.manager.attachment

import com.feedback.repository.remote.attachment.AttachmentObjectStorage
import com.feedback.model.attachment.DeleteAttachmentObjectCommand
import com.feedback.model.attachment.PutAttachmentObjectCommand
import com.feedback.service.attachment.*

import com.feedback.repository.attachment.*
import com.feedback.model.attachment.*
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.config.AttachmentObjectStorageProperties
import com.feedback.model.po.attachment.AttachmentObjectMigrationCandidatePo
import com.feedback.repository.attachment.SecureAttachmentRepository
import com.feedback.security.attachment.PlatformAttachmentStorageCrypto
import com.feedback.security.attachment.AttachmentLocalPathPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 在数据库事务外发布已验证附件。只有上传、完整读回哈希校验和单行 CAS 全部成功，对象才成为权威内容。
 * CAS 失败时补偿删除本次写入的对象；若并发 worker 已登记相同确定性对象键，则按幂等成功处理。
 */
@Component
class AttachmentObjectPublisher(
    private val repository: AttachmentObjectLifecycleRepository,
    private val attachmentReads: SecureAttachmentRepository,
    private val storageProvider: ObjectProvider<AttachmentObjectStorage>,
    private val storageCrypto: PlatformAttachmentStorageCrypto,
    private val properties: AttachmentObjectStorageProperties,
    private val localPaths: AttachmentLocalPathPolicy
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** 发布刚完成安全验证的指定附件；找不到候选时返回跳过。 */
    fun publishNew(attachmentId: Long): AttachmentObjectPublishResult =
        if (!properties.publishNew) AttachmentObjectPublishResult.DISABLED else publish(attachmentId)

    /** 按附件 ID 发布对象，先在数据库外读取候选，再进入统一发布流程。 */
    fun publish(attachmentId: Long): AttachmentObjectPublishResult {
        if (!properties.enabled) {
            return AttachmentObjectPublishResult.DISABLED
        }
        val candidate = repository.findObjectMigrationCandidate(attachmentId)
            ?: return attachmentReads.findObjectStorageLocation(attachmentId)
                ?.takeIf { it.lifecycleStatus == AttachmentObjectLifecycleStatus.AVAILABLE }
                ?.let { AttachmentObjectPublishResult.ALREADY_PUBLISHED }
                ?: AttachmentObjectPublishResult.NOT_ELIGIBLE
        return publish(candidate)
    }

    /** 在事务外解密、上传并回读哈希，最后以候选快照单条 CAS 登记对象位置。 */
    fun publish(candidate: AttachmentObjectMigrationCandidatePo): AttachmentObjectPublishResult {
        if (!properties.enabled) {
            return AttachmentObjectPublishResult.DISABLED
        }
        val storage = storageProvider.ifAvailable
            ?: throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, "ATTACHMENT_OBJECT_STORAGE_REQUIRED")
        val path = localPaths.readablePath(candidate.storagePath)
            ?: if (localPaths.isAllowedMissingPath(candidate.storagePath)) {
                throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, "ATTACHMENT_LOCAL_SOURCE_UNAVAILABLE")
            } else {
                throw ClientException(ErrorCode.BAD_REQUEST, "ATTACHMENT_LOCAL_SOURCE_INVALID")
            }
        val key = objectKey(candidate)
        var uploaded: StoredAttachmentObject? = null
        var plaintext: ByteArray? = null
        try {
            plaintext = storageCrypto.decryptForMigration(path, properties.migrationMaxInMemoryBytes)
            verifyLocalSource(plaintext!!, candidate)
            ByteArrayInputStream(plaintext).use { content ->
                uploaded = storage.put(
                    PutAttachmentObjectCommand(
                        key = key,
                        content = content,
                        contentLength = candidate.fileSizeBytes,
                        contentType = candidate.mimeType,
                        checksumSha256Base64 = Base64.getEncoder().encodeToString(candidate.fileSha256)
                    )
                )
            }
            verifyReadBack(storage, key, candidate)
            if (repository.markObjectAvailable(candidate, uploaded!!)) {
                logger.debug("Attachment object published attachment_id={} object_key_digest={}", candidate.id, keyDigest(key))
                return AttachmentObjectPublishResult.PUBLISHED
            }
            val current = attachmentReads.findObjectStorageLocation(candidate.id)
            if (current?.lifecycleStatus == AttachmentObjectLifecycleStatus.AVAILABLE && current.objectKey == key) {
                return AttachmentObjectPublishResult.ALREADY_PUBLISHED
            }
            storage.delete(DeleteAttachmentObjectCommand(key, uploaded?.version))
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "ATTACHMENT_OBJECT_LOCATION_CAS_FAILED")
        } catch (error: Throwable) {
            if (uploaded != null && attachmentReads.findObjectStorageLocation(candidate.id)?.objectKey != key) {
                runCatching { storage.delete(DeleteAttachmentObjectCommand(key, uploaded?.version)) }
                    .exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        } finally {
            plaintext?.fill(0)
        }
    }

    /** 完整读取已上传对象并校验长度、MIME 与 SHA-256，禁止只信任上传响应。 */
    private fun verifyReadBack(
        storage: AttachmentObjectStorage,
        key: AttachmentObjectKey,
        candidate: AttachmentObjectMigrationCandidatePo
    ) {
        storage.read(key).use { value ->
            serviceCheck(
                value.metadata.contentLength == candidate.fileSizeBytes,
                "ATTACHMENT_OBJECT_LENGTH_MISMATCH"
            )
            val digest = digestAndLength(value.content).sha256
            serviceCheck(MessageDigest.isEqual(digest, candidate.fileSha256), "ATTACHMENT_OBJECT_HASH_MISMATCH")
        }
    }

    /** 上传前在事务外完整校验本机解密流，明确区分源损坏与对象存储传输失败。 */
    private fun verifyLocalSource(content: ByteArray, candidate: AttachmentObjectMigrationCandidatePo) {
        serviceCheck(content.size.toLong() == candidate.fileSizeBytes, "ATTACHMENT_LOCAL_SOURCE_LENGTH_MISMATCH")
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        serviceCheck(MessageDigest.isEqual(digest, candidate.fileSha256), "ATTACHMENT_LOCAL_SOURCE_HASH_MISMATCH")
    }

    /** 统一执行发布完整性校验，失败时阻止对象登记。 */
    private fun serviceCheck(condition: Boolean, reasonCode: String) {
        if (!condition) {
            throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, reasonCode)
        }
    }

    /** 流式计算输入 SHA-256 与长度，不把对象整体装入额外内存。 */
    private fun digestAndLength(input: InputStream): DigestedContent {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var length = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            if (read == 0) {
                continue
            }
            digest.update(buffer, 0, read)
            length += read
        }
        buffer.fill(0)
        return DigestedContent(length, digest.digest())
    }

    /** 对象流的长度与摘要，避免完整内容二次驻留内存。 */
    private data class DigestedContent(val length: Long, val sha256: ByteArray)

    /** 以来源、所有者和内容摘要构造确定性私有对象键，重试不会产生新键。 */
    private fun objectKey(candidate: AttachmentObjectMigrationCandidatePo): AttachmentObjectKey {
        val source = candidate.sourceKind.lowercase().replace(Regex("[^a-z0-9_-]"), "-").take(32).ifBlank { "unknown" }
        val digest = candidate.fileSha256.joinToString("") { "%02x".format(it) }
        val extension = when (candidate.mimeType.lowercase()) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            "video/mp4" -> ".mp4"
            "video/webm" -> ".webm"
            "text/plain" -> ".log"
            "application/zip", "application/x-zip-compressed" -> ".zip"
            "application/gzip", "application/x-gzip" -> ".gz"
            "application/x-tar" -> ".tar"
            else -> ".bin"
        }
        return AttachmentObjectKey("attachments/$source/${candidate.id}/${digest.take(24)}$extension")
    }

    /** 对对象键做日志安全摘要，审计不记录完整私有键。 */
    private fun keyDigest(key: AttachmentObjectKey): String = MessageDigest.getInstance("SHA-256")
        .digest(key.value.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
}

/**
 * 幂等历史迁移 worker。数据库本身就是断点：`AVAILABLE` 行退出迁移候选，失败行留待后续有界重试；
 * 本机副本只委托给四重验证后的精确清理端口处理。
 */
@Component
class AttachmentObjectMigrationWorker(
    private val repository: AttachmentObjectLifecycleRepository,
    private val publisher: AttachmentObjectPublisher,
    private val properties: AttachmentObjectStorageProperties,
    private val cleanup: AttachmentLocalCopyCleaner,
    private val audit: AttachmentMigrationAudit
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var afterId: Long = 0

    /** 按 ID 游标有界迁移，单条失败可重试且不会持有跨对象 I/O 的数据库事务。 */
    fun migrateBatch() {
        if (!properties.enabled || !properties.migrationEnabled) {
            return
        }
        var cleanupDeleted = 0
        var cleanupFailed = 0
        if (properties.cleanupLocalAfterPublish) {
            repository.findObjectCleanupCandidates(properties.migrationBatchSize).forEach { candidate ->
                runCatching { cleanup.cleanup(candidate) }
                    .onSuccess { if (it == AttachmentLocalCleanupResult.DELETED) cleanupDeleted++ }
                    .onFailure { error ->
                        cleanupFailed++
                        logger.error(
                            "Attachment local cleanup failed attachment_id={} error_code={} error_type={}",
                            candidate.id, "ATTACHMENT_LOCAL_CLEANUP_FAILED", error.javaClass.simpleName
                        )
                    }
            }
        }
        var candidates = repository.findObjectMigrationCandidates(afterId, properties.migrationBatchSize)
        if (candidates.isEmpty() && afterId > 0) {
            afterId = 0
            candidates = repository.findObjectMigrationCandidates(afterId, properties.migrationBatchSize)
        }
        candidates.lastOrNull()?.let { afterId = it.id }
        if (properties.migrationDryRun) {
            logger.info(
                "Attachment object migration dry-run candidates={} bytes={}",
                candidates.size,
                candidates.sumOf { it.fileSizeBytes }
            )
            return
        }
        var published = 0
        var failed = 0
        var publishedBytes = 0L
        for (candidate in candidates) {
            if (properties.cleanupLocalAfterPublish && !cleanup.hasDiskHeadroom(candidate.fileSizeBytes)) {
                logger.error(
                    "Attachment object migration paused below disk safety watermark attachment_id={} bytes={} minimum_free_bytes={}",
                    candidate.id, candidate.fileSizeBytes, properties.minimumFreeDiskBytes
                )
                break
            }
            var lastError: Throwable? = null
            var completed = false
            repeat(properties.migrationRetryAttempts) { attempt ->
            if (completed) {
                return@repeat
            }
                runCatching { publisher.publish(candidate) }
                    .onSuccess {
                        completed = true
                        lastError = null
                        published++
                        publishedBytes += candidate.fileSizeBytes
                    }
                    .onFailure { error ->
                        lastError = error
                        logger.warn(
                            "Attachment object migration retry attachment_id={} attempt={} error_type={} reason={}",
                            candidate.id, attempt + 1, error.javaClass.simpleName, error.message?.take(160)
                        )
                    }
            }
            lastError?.let { error ->
                failed++
                if (error is ServiceException && error.reasonCode == "ATTACHMENT_LOCAL_SOURCE_UNAVAILABLE" &&
                    repository.markObjectMigrationSourceUnavailable(candidate)
                ) {
                    audit.recordSourceUnavailable(candidate)
                    logger.error(
                        "Attachment object migration source classified unavailable attachment_id={} error_code={}",
                        candidate.id,
                        "ATTACHMENT_LOCAL_SOURCE_UNAVAILABLE"
                    )
                    return@let
                }
                logger.error(
                    "Attachment object migration failed attachment_id={} error_code={} error_type={} reason={}",
                    candidate.id,
                    "ATTACHMENT_OBJECT_MIGRATION_FAILED",
                    error.javaClass.simpleName,
                    error.message?.take(160)
                )
            }
        }
        val remaining = repository.findObjectMigrationCandidates(0, 1).size
        val cleanupRemaining = if (properties.cleanupLocalAfterPublish) repository.findObjectCleanupCandidates(1).size else 0
        logger.info(
            "Attachment migration batch summary candidates={} published={} published_bytes={} failed={} cleanup_deleted={} cleanup_failed={} migration_remaining={} cleanup_remaining={} complete={}",
            candidates.size, published, publishedBytes, failed, cleanupDeleted, cleanupFailed,
            remaining, cleanupRemaining, remaining == 0 && cleanupRemaining == 0
        )
    }
}
