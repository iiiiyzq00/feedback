package com.feedback.service.impl.attachment

import com.feedback.manager.attachment.renderRawLogPreview
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.repository.attachment.AttachmentPreviewRepository
import com.feedback.manager.attachment.AttachmentContentProvider
import com.feedback.service.attachment.AttachmentLogPreviewService
import com.feedback.model.attachment.EncryptedLogPreview
import com.feedback.security.attachment.PlatformAttachmentStorageCrypto
import com.feedback.service.attachment.STORED_LOG_PREVIEW_LIMIT_BYTES
import com.feedback.model.attachment.SecureAttachmentContent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 加密日志预览服务实现。
 *
 * 文件读取、解压与加密位于事务外；写入采用单条状态条件 CAS，失败后以独立条件更新记录失败状态。
 */
@Service
class AttachmentLogPreviewServiceImpl(
    private val repository: AttachmentPreviewRepository,
    private val provider: AttachmentContentProvider,
    private val crypto: PlatformAttachmentStorageCrypto
) : AttachmentLogPreviewService {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** 生成固定上限的预览并确保临时明文字节无论成功失败都被清零。 */
    override fun createEncryptedPreview(content: SecureAttachmentContent): EncryptedLogPreview {
        val plaintext = content.renderRawLogPreview(STORED_LOG_PREVIEW_LIMIT_BYTES)
        try {
            return EncryptedLogPreview(crypto.encryptBytes(plaintext), plaintext.size)
        } finally {
            plaintext.fill(0)
        }
    }

    /** 一次最多处理四条记录；每条文件 I/O 与单条 CAS 相互隔离，单条失败不阻断整批。 */
    override fun backfillBatch() {
        repository.findMissingLogPreviewIds(BACKFILL_BATCH_SIZE).forEach(::backfillOne)
    }

    /** 先在事务外生成密文，再以条件 SQL 落库；竞争失败与生成失败统一收敛为可审计失败码。 */
    private fun backfillOne(attachmentId: Long) {
        runCatching {
            val preview = createEncryptedPreview(provider.openForLogPreviewGeneration(attachmentId))
            if (!repository.storeLogPreview(attachmentId, preview.ciphertext, crypto.keyId, preview.plaintextBytes)) {
                throw ServiceException(ErrorCode.INTERNAL_ERROR, "ATTACHMENT_LOG_PREVIEW_CAS_FAILED")
            }
        }.onFailure { error ->
            repository.markLogPreviewFailed(attachmentId, LOG_PREVIEW_FAILURE_CODE)
            logger.error(
                "Log preview backfill failed attachment_id={} error_code={} error_type={}",
                attachmentId,
                LOG_PREVIEW_FAILURE_CODE,
                error.javaClass.simpleName
            )
        }
    }

    private companion object {
        const val BACKFILL_BATCH_SIZE = 4
        const val LOG_PREVIEW_FAILURE_CODE = "ATTACHMENT_LOG_PREVIEW_GENERATION_FAILED"
    }
}
