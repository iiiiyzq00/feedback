package com.feedback.manager.attachment

import com.feedback.repository.remote.attachment.AttachmentObjectStorage
import com.feedback.service.attachment.*

import com.feedback.repository.attachment.*
import com.feedback.model.attachment.*
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.config.AttachmentObjectStorageProperties
import com.feedback.model.po.attachment.AttachmentObjectCleanupCandidatePo
import com.feedback.security.attachment.AttachmentLocalPathPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** 发布与批次编排依赖的清理端口；调用方不感知文件系统和审计实现细节。 */
interface AttachmentLocalCopyCleaner {
    /** 按附件 ID 读取最新快照并清理本地副本。 */
    fun cleanup(attachmentId: Long): AttachmentLocalCleanupResult

    /** 校验候选对象与本地快照后执行精确单文件删除和清路径 CAS。 */
    fun cleanup(candidate: AttachmentObjectCleanupCandidatePo): AttachmentLocalCleanupResult

    /** 判断 MinIO 数据盘是否仍有足够余量接收下一对象。 */
    fun hasDiskHeadroom(nextObjectBytes: Long): Boolean
}

/**
 * 只有重新完整读回对象且重新读取 `AVAILABLE` 行后，才精确删除一个本机密文副本。
 * 本组件不提供对象存储删除能力，也不接受目录或通配符作为目标。
 */
@Component
class AttachmentLocalCopyCleanup(
    private val repository: AttachmentObjectLifecycleRepository,
    private val storageProvider: ObjectProvider<AttachmentObjectStorage>,
    private val properties: AttachmentObjectStorageProperties,
    private val localPaths: AttachmentLocalPathPolicy,
    private val audit: AttachmentMigrationAudit
) : AttachmentLocalCopyCleaner {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** 读取指定附件最新清理快照，找不到时视为无需处理。 */
    override fun cleanup(attachmentId: Long): AttachmentLocalCleanupResult {
        if (!properties.cleanupLocalAfterPublish) {
            return AttachmentLocalCleanupResult.DISABLED
        }
        val candidate = repository.findObjectCleanupCandidate(attachmentId)
            ?: return AttachmentLocalCleanupResult.NOT_PENDING
        return cleanup(candidate)
    }

    /** 先回读并校验对象，再复核数据库快照，最终只删除一个白名单内普通文件。 */
    override fun cleanup(candidate: AttachmentObjectCleanupCandidatePo): AttachmentLocalCleanupResult {
        ensure(properties.cleanupLocalAfterPublish, "ATTACHMENT_LOCAL_CLEANUP_DISABLED")
        ensure(
            candidate.objectStorageStatus == AttachmentObjectLifecycleStatus.AVAILABLE.code,
            "ATTACHMENT_LOCAL_CLEANUP_DB_NOT_AVAILABLE"
        )
        ensure(candidate.objectBucket == properties.bucket, "ATTACHMENT_LOCAL_CLEANUP_BUCKET_MISMATCH")
        val key = AttachmentObjectKey(candidate.objectKey)
        val storage = storageProvider.ifAvailable ?: failure("ATTACHMENT_OBJECT_STORAGE_REQUIRED")

        verifyObject(storage, key, candidate)
        val fresh = repository.findObjectCleanupCandidate(candidate.id)
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "ATTACHMENT_LOCAL_CLEANUP_DB_CHANGED")
        ensure(sameFacts(candidate, fresh), "ATTACHMENT_LOCAL_CLEANUP_DB_CHANGED")

        val path = localPaths.requireAllowedPath(fresh.storagePath)
        val minioRoot = Path.of(properties.minioDataDir).toAbsolutePath().normalize()
        val realMinioRoot = runCatching { minioRoot.toRealPath() }.getOrDefault(minioRoot)
        ensure(!path.startsWith(realMinioRoot), "ATTACHMENT_LOCAL_CLEANUP_MINIO_PATH_FORBIDDEN")
        val deletedNow = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        if (deletedNow) {
            ensure(
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path),
                "ATTACHMENT_LOCAL_CLEANUP_NOT_REGULAR_FILE"
            )
            audit.recordCleanup(fresh, AttachmentCleanupAuditOutcome.VERIFIED_DELETE_INTENT)
            // 单文件删除前再次解析路径；全程不调用目录、递归或通配符删除 API。
            val finalPath = localPaths.requireReadablePath(fresh.storagePath)
            ensure(finalPath == path, "ATTACHMENT_LOCAL_CLEANUP_PATH_CHANGED")
            try {
                Files.delete(finalPath)
            } catch (error: Throwable) {
                audit.recordCleanup(fresh, AttachmentCleanupAuditOutcome.DELETE_FAILED)
                throw error
            }
            audit.recordCleanup(fresh, AttachmentCleanupAuditOutcome.LOCAL_FILE_DELETED)
        }
        // 精确文件已不存在时，按“删除后、短事务 CAS 前崩溃”的可恢复状态处理。
        if (!repository.clearVerifiedLocalCopy(fresh)) {
            audit.recordCleanup(
                fresh,
                if (deletedNow) {
                    AttachmentCleanupAuditOutcome.LOCAL_DELETED_DB_CLEAR_FAILED
                } else {
                    AttachmentCleanupAuditOutcome.ABSENT_DB_CLEAR_FAILED
                }
            )
            failure("ATTACHMENT_LOCAL_CLEANUP_DB_CLEAR_CAS_FAILED")
        }
        audit.recordCleanup(
            fresh,
            if (deletedNow) {
                AttachmentCleanupAuditOutcome.DELETED
            } else {
                AttachmentCleanupAuditOutcome.ALREADY_ABSENT_RECONCILED
            }
        )
        logger.debug(
            "Attachment local copy deleted attachment_id={} bytes={} path_digest={} object_key_digest={}",
            fresh.id, fresh.fileSizeBytes, digestText(fresh.storagePath), digestText(fresh.objectKey)
        )
        return AttachmentLocalCleanupResult.DELETED
    }

    /** 根据可用空间与保留阈值判断数据盘余量；配置缺失时失败关闭。 */
    override fun hasDiskHeadroom(nextObjectBytes: Long): Boolean {
        if (properties.minioDataDir.isBlank()) {
            return false
        }
        val dataDir = Path.of(properties.minioDataDir).toAbsolutePath().normalize()
        val usable = Files.getFileStore(dataDir).usableSpace
        return usable - nextObjectBytes >= properties.minimumFreeDiskBytes
    }

    /** 流式回读对象并校验 bucket、key、版本、长度和 SHA-256。 */
    private fun verifyObject(
        storage: AttachmentObjectStorage,
        key: AttachmentObjectKey,
        candidate: AttachmentObjectCleanupCandidatePo
    ) {
        storage.read(key).use { value ->
            ensure(value.metadata.bucket == candidate.objectBucket, "ATTACHMENT_LOCAL_CLEANUP_BUCKET_MISMATCH")
            ensure(value.metadata.key == key, "ATTACHMENT_LOCAL_CLEANUP_KEY_MISMATCH")
            ensure(value.metadata.contentLength == candidate.fileSizeBytes, "ATTACHMENT_LOCAL_CLEANUP_LENGTH_MISMATCH")
            if (!candidate.objectVersion.isNullOrBlank()) {
                ensure(value.metadata.version == candidate.objectVersion, "ATTACHMENT_LOCAL_CLEANUP_VERSION_MISMATCH")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = value.content.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
            buffer.fill(0)
            ensure(MessageDigest.isEqual(digest.digest(), candidate.fileSha256), "ATTACHMENT_LOCAL_CLEANUP_HASH_MISMATCH")
        }
    }

    /** 统一执行清理不变量，任何失败都阻止本地删除。 */
    private fun ensure(valid: Boolean, reasonCode: String) {
        if (!valid) {
            failure(reasonCode)
        }
    }

    /** 构造稳定服务错误，调用方据此记录清理审计。 */
    private fun failure(reasonCode: String): Nothing =
        throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, reasonCode)

    /** 比较删除所需的完整并发快照，任一字段变化都拒绝继续。 */
    private fun sameFacts(left: AttachmentObjectCleanupCandidatePo, right: AttachmentObjectCleanupCandidatePo): Boolean =
        left.id == right.id && left.storagePath == right.storagePath && left.fileSizeBytes == right.fileSizeBytes &&
            MessageDigest.isEqual(left.fileSha256, right.fileSha256) && left.objectBucket == right.objectBucket &&
            left.objectKey == right.objectKey && left.objectVersion == right.objectVersion &&
            right.objectStorageStatus == AttachmentObjectLifecycleStatus.AVAILABLE.code

    /** 对敏感路径或对象键生成审计摘要。 */
    private fun digestText(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).toHex()

    /** 将摘要字节编码为稳定小写十六进制。 */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * 新写附件的独立清理循环。发布器只负责上传、校验和 CAS；本 worker 在事务提交后重试本机清理，
 * 因此文件系统故障不会回滚或掩盖已经成立的 `AVAILABLE` 权威状态。
 */
@Component
class AttachmentLocalCleanupWorker(
    private val repository: AttachmentObjectLifecycleRepository,
    private val cleaner: AttachmentLocalCopyCleaner,
    private val properties: AttachmentObjectStorageProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** 仅在迁移完成后有界清理本地副本，单条失败不会阻断后续候选。 */
    fun cleanupBatch() {
        if (!properties.enabled || !properties.cleanupLocalAfterPublish || properties.migrationEnabled) {
            return
        }
        repository.findObjectCleanupCandidates(properties.migrationBatchSize).forEach { candidate ->
            runCatching { cleaner.cleanup(candidate) }.onFailure { error ->
                logger.error(
                    "Attachment new-write local cleanup deferred attachment_id={} error_type={}",
                    candidate.id, error.javaClass.simpleName
                )
            }
        }
    }
}
