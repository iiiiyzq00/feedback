package com.feedback.manager.attachment

import com.feedback.manager.attachment.INLINE_LOG_PREVIEW_LIMIT_BYTES
import com.feedback.manager.attachment.asInlineView
import com.feedback.model.attachment.AttachmentAccessGrant
import com.feedback.model.attachment.AttachmentContentSource
import com.feedback.model.attachment.AttachmentDeliveryMode
import com.feedback.model.attachment.SecureAttachmentContent
import com.feedback.security.attachment.AttachmentLocalPathPolicy
import com.feedback.security.attachment.PlatformAttachmentStorageCrypto
import com.feedback.model.attachment.AttachmentObjectKey
import com.feedback.audit.AuditTextDigester
import com.feedback.common.AttachmentGrantPurpose
import com.feedback.common.AttachmentAuthorizationAction
import com.feedback.common.AttachmentLogPreviewStatus
import com.feedback.common.AttachmentType
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.RoleCode
import com.feedback.repository.attachment.SecureAttachmentRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.ObjectProvider
import com.feedback.config.AttachmentObjectStorageProperties
import com.feedback.model.attachment.AttachmentObjectLifecycleStatus
import com.feedback.repository.remote.attachment.AttachmentObjectStorage
import com.feedback.manager.attachment.AttachmentGrantIssuePort
import com.feedback.manager.attachment.AttachmentGrantConsumePort
import com.feedback.manager.attachment.AttachmentGrantTransactionPort
import com.feedback.model.attachment.ConsumedAttachmentGrant
import com.feedback.application.port.feedback.attachment.AttachmentGrantFingerprint
import com.feedback.application.port.feedback.attachment.AttachmentGrantStorePort
import com.feedback.application.port.feedback.attachment.CreateAttachmentGrantCommand
import com.feedback.model.attachment.ConsumeFeedbackAttachmentCommand
import com.feedback.model.attachment.ConsumeOriginalLogAttachmentCommand
import com.feedback.model.attachment.ConsumeMediaPreviewCommand
import com.feedback.service.attachment.AttachmentSecureStreamService
import com.feedback.security.attachment.FeedbackAttachmentAccessPort
import com.feedback.application.port.feedback.attachment.FeedbackAttachmentReadPort
import com.feedback.model.attachment.IssueAttachmentGrantCommand
import org.springframework.stereotype.Component
import org.springframework.context.annotation.Primary
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import io.swagger.v3.oas.annotations.media.Schema
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.Base64

/**
 * 与 HTTP 和存储 SDK 无关的一次性内容源。调用方取得流后必须关闭；实现允许延迟到响应写出阶段
 * 才打开本地密文或返回已取得的对象流，且不得在一次请求中重复打开。
 */
/**
 * 一次性附件安全流服务。
 *
 * 签发由调用方的授权审计短事务包围；消费提交后才打开本地文件或对象流，避免长事务占用连接。
 */
interface AttachmentContentProvider {
    /**
     * 打开已通过授权消费的附件内容。实现不得根据用户输入拼接越界路径，也不得记录授权令牌。
     * 返回 Resource 可以延迟到 HTTP 写出阶段才真正打开底层流，因此晚发存储错误不会恢复已提交的令牌。
     */
    fun open(attachmentId: Long): SecureAttachmentContent

    /** 打开已预计算的有界日志预览；尚未生成时返回 null。 */
    fun openLogPreview(attachmentId: Long): SecureAttachmentContent? = null

    /** 打开已发布的私有媒体派生预览；尚未生成时返回 null。 */
    fun openMediaPreview(attachmentId: Long): SecureAttachmentContent? = null

    /** 为后台日志预览生成打开安全原件，不执行用户授权。 */
    fun openForLogPreviewGeneration(attachmentId: Long): SecureAttachmentContent = open(attachmentId)
}

/**
 * 本地隔离区内容提供器；只接受权威附件行中的规范化路径，路径必须位于配置根目录、是普通文件且不是符号链接。
 * 开关关闭、权威行不可查看、路径越界或文件形态异常均失败关闭，不回退到未加密文件直读。
 */
@Component
@Primary
class LocalQuarantineAwareAttachmentContentProvider(
    private val attachmentRepository: SecureAttachmentRepository,
    private val storageCrypto: PlatformAttachmentStorageCrypto,
    private val localPaths: AttachmentLocalPathPolicy,
    @Value("\${app.attachment.secure-stream.enabled:false}") private val enabled: Boolean,
    private val objectStorageProvider: ObjectProvider<AttachmentObjectStorage>? = null,
    private val objectStorageProperties: AttachmentObjectStorageProperties = AttachmentObjectStorageProperties()
) : AttachmentContentProvider {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** 优先读取已验证对象；否则只从白名单根打开本地密文，任何异常均失败关闭。 */
    override fun open(attachmentId: Long): SecureAttachmentContent {
        if (!enabled) unavailable("ATTACHMENT_SECURE_STREAM_DISABLED")
        val row = attachmentRepository.findViewableContent(attachmentId)
            ?: notFound()
        val objectLocation = attachmentRepository.findObjectStorageLocation(attachmentId)
        if (objectLocation?.lifecycleStatus == AttachmentObjectLifecycleStatus.AVAILABLE) {
            if (!objectStorageProperties.enabled || objectLocation.bucket != objectStorageProperties.bucket) {
                unavailable("ATTACHMENT_OBJECT_STORAGE_UNAVAILABLE")
            }
            val objectStorage = objectStorageProvider?.ifAvailable
                ?: unavailable("ATTACHMENT_OBJECT_STORAGE_UNAVAILABLE")
            val value = objectStorage.read(objectLocation.objectKey)
            try {
                if (value.metadata.contentLength != row.fileSizeBytes) serviceFailure("ATTACHMENT_OBJECT_LENGTH_MISMATCH")
                if (value.metadata.contentType != null && value.metadata.contentType != row.mimeType) {
                    serviceFailure("ATTACHMENT_OBJECT_CONTENT_TYPE_MISMATCH")
                }
                return SecureAttachmentContent(
                    AttachmentContentSource { value.content },
                    row.mimeType,
                    "attachment-$attachmentId${safeExtension(objectLocation.objectKey.value)}",
                    value.metadata.contentLength
                )
            } catch (error: Throwable) {
                value.close()
                throw error
            }
        }
        val path = row.storagePath?.let(localPaths::readablePath)
            ?: notFound()
        val source = AttachmentContentSource {
            try {
                storageCrypto.open(path)
            } catch (error: Exception) {
                logger.error(
                    "Secure attachment open failed attachment_id={} error_type={} error_code=ATTACHMENT_STORAGE_OPEN_FAILED",
                    attachmentId,
                    error.javaClass.simpleName
                )
                throw error
            }
        }
        return SecureAttachmentContent(
            source,
            row.mimeType,
            "attachment-$attachmentId${safeExtension(path)}",
            row.fileSizeBytes?.takeIf { it >= 0 }
        )
    }

    /** 只解密状态、密钥标识和长度均匹配的有界日志预览。 */
    override fun openLogPreview(attachmentId: Long): SecureAttachmentContent? {
        if (!enabled) unavailable("ATTACHMENT_SECURE_STREAM_DISABLED")
        val row = attachmentRepository.findViewableContent(attachmentId)
            ?: notFound()
        if (row.attachmentType != AttachmentType.LOG || row.logPreviewStatus != AttachmentLogPreviewStatus.AVAILABLE) {
            return null
        }
        if (row.logPreviewKeyId != storageCrypto.keyId) {
            serviceFailure("ATTACHMENT_LOG_PREVIEW_KEY_MISMATCH")
        }
        val plaintext = storageCrypto.decryptBytes(
            row.logPreviewCiphertext ?: serviceFailure("ATTACHMENT_LOG_PREVIEW_MISSING")
        )
        if (plaintext.size != row.logPreviewPlaintextBytes) {
            serviceFailure("ATTACHMENT_LOG_PREVIEW_LENGTH_MISMATCH")
        }
        return SecureAttachmentContent(
            AttachmentContentSource { java.io.ByteArrayInputStream(plaintext) },
            "text/plain;charset=UTF-8",
            "attachment-$attachmentId-preview.log",
            plaintext.size.toLong()
        )
    }

    /** 先复核原件仍可查看，再从私有对象存储打开 JPEG 派生预览。 */
    override fun openMediaPreview(attachmentId: Long): SecureAttachmentContent? {
        if (!enabled) unavailable("ATTACHMENT_SECURE_STREAM_DISABLED")
        // 原件当前仍须可查看，防止删除、过期或安全状态变化后继续读取旧派生对象。
        attachmentRepository.findViewableContent(attachmentId) ?: notFound()
        val location = attachmentRepository.findMediaPreviewLocation(attachmentId) ?: return null
        if (!objectStorageProperties.enabled) unavailable("ATTACHMENT_OBJECT_STORAGE_UNAVAILABLE")
        val objectStorage = objectStorageProvider?.ifAvailable
            ?: unavailable("ATTACHMENT_OBJECT_STORAGE_UNAVAILABLE")
        val value = objectStorage.read(location.objectKey)
        try {
            if (value.metadata.contentLength != location.contentLength ||
                value.metadata.contentType != "image/jpeg") {
                serviceFailure("ATTACHMENT_MEDIA_PREVIEW_OBJECT_MISMATCH")
            }
            return SecureAttachmentContent(
                AttachmentContentSource { value.content },
                "image/jpeg",
                "attachment-$attachmentId-preview.jpg",
                value.metadata.contentLength
            )
        } catch (error: Throwable) {
            value.close()
            throw error
        }
    }

    /** 后台生成日志预览时优先复用对象内容，否则有界解密本地迁移文件。 */
    override fun openForLogPreviewGeneration(attachmentId: Long): SecureAttachmentContent {
        if (!enabled) unavailable("ATTACHMENT_SECURE_STREAM_DISABLED")
        val row = attachmentRepository.findViewableContent(attachmentId)
            ?: notFound()
        if (row.attachmentType != AttachmentType.LOG) {
            serviceFailure("ATTACHMENT_LOG_PREVIEW_TYPE_INVALID")
        }
        if (attachmentRepository.findObjectStorageLocation(attachmentId)?.lifecycleStatus == AttachmentObjectLifecycleStatus.AVAILABLE) {
            return open(attachmentId)
        }
        val path = row.storagePath?.let(localPaths::readablePath)
            ?: notFound()
        val plaintext = storageCrypto.decryptForMigration(path, LOG_PREVIEW_BACKFILL_MAX_PLAINTEXT_BYTES)
        return SecureAttachmentContent(
            wipingContentSource(plaintext),
            row.mimeType,
            "attachment-$attachmentId-preview-source",
            plaintext.size.toLong()
        )
    }

    /** 从路径文件名提取受限扩展名，不回传目录信息。 */
    private fun safeExtension(path: java.nio.file.Path): String = safeExtension(path.fileName.toString())

    /** 仅允许固定展示扩展名集合，其余统一降级为 `.bin`。 */
    private fun safeExtension(name: String): String {
        val suffix = name.substringAfterLast('.', "").lowercase()
        return if (suffix in setOf("jpg","jpeg","png","webp","mp4","webm","txt","log","gz","gzip","zip")) ".$suffix" else ".bin"
    }

    /** 以统一 404 隐藏附件不存在与安全门失败的差异。 */
    private fun notFound(): Nothing = throw ClientException(ErrorCode.NOT_FOUND, "ATTACHMENT_NOT_AVAILABLE")

    /** 将依赖不可用转换为附件服务不可用错误。 */
    private fun unavailable(reasonCode: String): Nothing =
        throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, reasonCode)
    /** 将对象或密文完整性异常转换为内部服务错误。 */
    private fun serviceFailure(reasonCode: String): Nothing =
        throw ServiceException(ErrorCode.INTERNAL_ERROR, reasonCode)
}

private const val LOG_PREVIEW_BACKFILL_MAX_PLAINTEXT_BYTES = 512L * 1024L * 1024L

/** 包装内存明文流，关闭时无条件清零原始字节数组。 */
private fun wipingContentSource(bytes: ByteArray) = AttachmentContentSource {
    val delegate = java.io.ByteArrayInputStream(bytes)
    object : java.io.FilterInputStream(delegate) {
            /** 关闭底层流后清零明文字节，异常路径同样执行清零。 */
            override fun close() {
                try {
                    super.close()
                } finally {
                    bytes.fill(0)
                }
            }
        }
}

/** 安全流关闭时清零内存明文；未配置真实提供器时不允许回退。 */
@Component
class DisabledAttachmentContentProvider(
    @Value("\${app.attachment.secure-stream.enabled:false}") private val enabled: Boolean
) : AttachmentContentProvider {
    /** 未配置安全提供器时始终失败关闭，不回退到普通文件读取。 */
    override fun open(attachmentId: Long): SecureAttachmentContent {
        val message = if (enabled) "ATTACHMENT_SECURE_PROVIDER_NOT_CONFIGURED" else "ATTACHMENT_SECURE_STREAM_DISABLED"
        throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, message)
    }
}

/**
 * 一次性附件令牌的创建与消费边界。
 *
 * 授权只持久化随机令牌的 SHA-256 指纹；原始令牌仅通过当前响应交给调用方，禁止日志记录或长期保存。
 * 消费查询把指纹、附件、反馈和当前用户绑定，并以 `FOR UPDATE` 锁定候选；随后条件更新 consumed_at，
 * 因而并发、过期、撤销和已消费请求只能有一个成功。日志内容在消费后转换为受限的页面内视图。
 */
internal class AttachmentSecureStreamOperations(
    private val grantRepository: AttachmentGrantStorePort,
    private val provider: AttachmentContentProvider,
    private val grantConsumer: AttachmentGrantConsumePort,
    @Value("\${app.attachment.secure-stream.ttl-seconds:300}") private val ttlSeconds: Long
) {
    private val random = SecureRandom()
    private val logger = LoggerFactory.getLogger(javaClass)

    /** 创建 30～300 秒授权；调用方负责在同一外层事务内完成附件实时状态校验和授权审计。 */
    /** 创建一次性授权；数据库插入由调用方的授权审计短事务包围。 */
    fun issue(command: IssueAttachmentGrantCommand): AttachmentAccessGrant = createGrant(
        command.sourceId,
        command.attachmentId,
        command.action,
        command.principal,
        command.purpose
    )

    /** 生成随机令牌并仅持久化指纹；用途编码决定稳定 URL 与存储前缀。 */
    private fun createGrant(
        sourceId: Long,
        attachmentId: Long,
        action: AttachmentAuthorizationAction,
        principal: WebPrincipal,
        purpose: AttachmentGrantPurpose
    ): AttachmentAccessGrant {
        if (ttlSeconds !in 30..300) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "ATTACHMENT_GRANT_TTL_INVALID")
        }
        val actionDigest = AuditTextDigester.digest(action.code)
        val raw = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val expiresAt = LocalDateTime.now().plusSeconds(ttlSeconds)
        grantRepository.create(
            CreateAttachmentGrantCommand(
                sourceId = sourceId,
                attachmentId = attachmentId,
                userId = principal.userId,
                fingerprint = AttachmentGrantFingerprint.fromToken(token),
                expiresAt = expiresAt,
                accessReason = when (purpose) {
                    AttachmentGrantPurpose.INLINE_VIEW -> actionDigest.storageValue()
                    AttachmentGrantPurpose.DERIVED_PREVIEW -> "DERIVED_PREVIEW|${actionDigest.storageValue()}"
                    AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD -> "ORIGINAL_LOG_DOWNLOAD|${actionDigest.storageValue()}"
                }
            )
        )
        return AttachmentAccessGrant(
            grantId = token,
            expiresAt = expiresAt,
            expiresInSeconds = ttlSeconds,
            delivery = when (purpose) {
                AttachmentGrantPurpose.INLINE_VIEW -> AttachmentDeliveryMode.ONE_TIME_SECURE_STREAM
                AttachmentGrantPurpose.DERIVED_PREVIEW -> AttachmentDeliveryMode.ONE_TIME_DERIVED_PREVIEW
                AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD -> AttachmentDeliveryMode.ONE_TIME_ORIGINAL_DOWNLOAD
            },
            downloadUrl = when (purpose) {
                AttachmentGrantPurpose.INLINE_VIEW -> "/api/v1/feedback/$sourceId/attachments/$attachmentId/stream?grantId=$token"
                AttachmentGrantPurpose.DERIVED_PREVIEW -> "/api/v1/feedback/$sourceId/attachments/$attachmentId/preview?grantId=$token"
                AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD -> "/api/v1/feedback/$sourceId/attachments/$attachmentId/original?grantId=$token"
            }
        )
    }

    /** 原子消费在短事务内完成；本地或 MinIO 内容只在事务提交后打开。 */
    fun consume(
        sourceId: Long,
        attachmentId: Long,
        token: String,
        purpose: AttachmentGrantPurpose = AttachmentGrantPurpose.INLINE_VIEW
    ): SecureAttachmentContent {
        grantConsumer.consume(sourceId, attachmentId, token, purpose)
        return provider.open(attachmentId)
    }

    /** 日志附件转换为页面安全视图；对象网络 I/O 不占用令牌消费事务。 */
    fun consumeForView(sourceId: Long, attachmentId: Long, token: String): SecureAttachmentContent {
        return consumeForView(sourceId, attachmentId, token, INLINE_LOG_PREVIEW_LIMIT_BYTES)
    }

    /** 日志缩略内容使用更小的调用方上限；图片和视频不受该参数影响。 */
    fun consumeForView(
        sourceId: Long,
        attachmentId: Long,
        token: String,
        previewLimitBytes: Long
    ): SecureAttachmentContent {
        if (previewLimitBytes !in 1_024..INLINE_LOG_PREVIEW_LIMIT_BYTES) {
            throw ClientException(ErrorCode.BAD_REQUEST, "INLINE_LOG_PREVIEW_LIMIT_INVALID")
        }
        val consumed = grantConsumer.consume(sourceId, attachmentId, token, AttachmentGrantPurpose.INLINE_VIEW)
        if (consumed.attachmentType != AttachmentType.LOG) {
            return provider.open(attachmentId)
        }
        val preview = provider.openLogPreview(attachmentId)
            ?: throw ServiceException(ErrorCode.ATTACHMENT_NOT_READY, "ATTACHMENT_LOG_PREVIEW_NOT_READY")
        return preview.asInlineView(consumed.principal, previewLimitBytes)
    }

    /** 原始日志下载不经过预览解压/截断/隐藏；用途、RAW 权限和实时安全状态全部失败关闭。 */
    fun consumeOriginalLog(sourceId: Long, attachmentId: Long, token: String): SecureAttachmentContent {
        grantConsumer.consume(sourceId, attachmentId, token, AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD)
        return provider.open(attachmentId)
    }

    /** 消费派生预览用途授权后打开私有预览对象。 */
    fun consumeMediaPreview(sourceId: Long, attachmentId: Long, token: String): SecureAttachmentContent {
        grantConsumer.consume(sourceId, attachmentId, token, AttachmentGrantPurpose.DERIVED_PREVIEW)
        return provider.openMediaPreview(attachmentId)
            ?: throw ServiceException(ErrorCode.ATTACHMENT_NOT_READY, "ATTACHMENT_MEDIA_PREVIEW_NOT_READY")
    }

    /** 消费普通查看授权，并在事务提交后返回安全内容源。 */
    fun stream(command: ConsumeFeedbackAttachmentCommand): SecureAttachmentContent =
        if (command.previewLimitBytes == null) {
            consumeForView(command.sourceId, command.attachmentId, command.grantId)
        } else {
            consumeForView(command.sourceId, command.attachmentId, command.grantId, command.previewLimitBytes)
        }

    /** 消费原始日志专用授权，并在提交后打开原件。 */
    fun original(command: ConsumeOriginalLogAttachmentCommand): SecureAttachmentContent =
        consumeOriginalLog(command.sourceId, command.attachmentId, command.grantId)

    /** 消费派生预览专用授权，并在提交后打开预览对象。 */
    fun preview(command: ConsumeMediaPreviewCommand): SecureAttachmentContent =
        consumeMediaPreview(command.sourceId, command.attachmentId, command.grantId)

}

/**
 * 一次性令牌消费的短事务边界。短事务内先复核当前数据范围，再校验归属、安全状态、用途和角色，
 * 最后锁定并消费 grant；提交后才打开本地文件或对象网络流，避免把数据库连接扩散到 MinIO I/O。
 */
@Component
class AttachmentGrantConsumer(
    private val grants: AttachmentGrantStorePort,
    private val attachments: FeedbackAttachmentReadPort,
    private val access: FeedbackAttachmentAccessPort,
    private val transactions: AttachmentGrantTransactionPort
) : AttachmentGrantConsumePort {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** 短事务内依次复核数据范围、安全事实、用途权限、行锁和消费 CAS。 */
    override fun consume(
        sourceId: Long,
        attachmentId: Long,
        token: String,
        purpose: AttachmentGrantPurpose
    ): ConsumedAttachmentGrant {
        if (token.length !in 40..80) {
            throw ClientException(ErrorCode.BAD_REQUEST, "ATTACHMENT_GRANT_INVALID")
        }
        return transactions.execute {
            // 签发后的权限可能被撤销；当前数据范围必须在附件事实和 grant 行锁之前重新校验。
            access.requireScope(sourceId)
            val principal = access.principal()
            val facts = attachments.findAuthorizationFacts(attachmentId, sourceId)
                ?: throw ClientException(ErrorCode.NOT_FOUND, "ATTACHMENT_NOT_AVAILABLE")
            if (!facts.isViewable()) {
                // 对不可查看记录统一返回 404，避免把安全状态差异暴露给调用方。
                throw ClientException(ErrorCode.NOT_FOUND, "ATTACHMENT_NOT_AVAILABLE")
            }
            if (purpose == AttachmentGrantPurpose.ORIGINAL_LOG_DOWNLOAD) {
                if (RoleCode.ADMIN !in principal.roles &&
                    !com.feedback.security.ContentVisibility.canViewRaw(principal)
                ) throw ClientException(ErrorCode.AUTH_FORBIDDEN, "ORIGINAL_LOG_DOWNLOAD_RAW_PERMISSION_REQUIRED")
                if (facts.attachmentType != AttachmentType.LOG) {
                    throw ClientException(ErrorCode.BAD_REQUEST, "ORIGINAL_LOG_DOWNLOAD_LOG_REQUIRED")
                }
            }
            val grantId = grants.lockConsumableId(
                fingerprint = AttachmentGrantFingerprint.fromToken(token),
                attachmentId = attachmentId,
                sourceId = sourceId,
                userId = principal.userId,
                purpose = purpose
            )
            if (grantId == null || !grants.markConsumed(grantId)) {
                logger.warn(
                    "Attachment grant rejected source_id={} attachment_id={} user_id={} reason=INVALID_EXPIRED_OR_CONSUMED",
                    sourceId, attachmentId, principal.userId
                )
                throw ClientException(ErrorCode.ATTACHMENT_ACCESS_CONFLICT, "ATTACHMENT_GRANT_INVALID_EXPIRED_OR_CONSUMED")
            }
            ConsumedAttachmentGrant(facts.attachmentType, principal)
        }
    }
}

/** 只包装 grant 消费短事务；对象存储或本地文件流不会进入该事务。 */
@Component
class SpringAttachmentGrantTransactionAdapter(
    private val transactions: TransactionOperations
) : AttachmentGrantTransactionPort {
    /** 执行 grant 消费短事务；空结果视为内部事务异常。 */
    override fun execute(action: () -> ConsumedAttachmentGrant): ConsumedAttachmentGrant =
        transactions.execute { action() }
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "ATTACHMENT_GRANT_TRANSACTION_EMPTY")
}
