package com.feedback.service.attachment

import com.feedback.model.attachment.EncryptedLogPreview
import com.feedback.model.attachment.SecureAttachmentContent

internal const val STORED_LOG_PREVIEW_LIMIT_BYTES = 512L * 1024L

/**
 * 加密日志预览服务。
 *
 * 生成固定上限的派生预览并执行有界回填；HTTP 查看只解密预览，不重复打开完整归档。
 */
interface AttachmentLogPreviewService {
    /** 在事务外读取并生成有界密文预览，完成后清零明文字节。 */
    fun createEncryptedPreview(content: SecureAttachmentContent): EncryptedLogPreview

    /** 有界读取一批候选并逐条执行幂等 CAS，不持有跨文件 I/O 的数据库事务。 */
    fun backfillBatch()
}
