package com.feedback.repository.impl.attachment

import com.feedback.mapper.attachment.FeedbackAttachmentMapper
import com.feedback.model.po.attachment.AttachmentScanCandidatePo
import com.feedback.repository.attachment.AttachmentSecurityScanRepository
import org.springframework.stereotype.Repository

/**
 * 基于 MyBatis 的附件扫描状态仓储实现。
 *
 * 所有写入均为单条条件 SQL，不额外开启事务；外部扫描、加密和文件删除绝不进入本类。
 */
@Repository
class MyBatisAttachmentSecurityScanRepositoryImpl(
    private val mapper: FeedbackAttachmentMapper
) : AttachmentSecurityScanRepository {
    /** 将批量大小限制在 1～100，防止后台任务无界读取。 */
    override fun pending(limit: Int): List<AttachmentScanCandidatePo> =
        mapper.findPendingSecurityScan(limit.coerceIn(1, MAX_SCAN_BATCH_SIZE))

    /** 条件登记密文与验证状态；调用方根据行数决定是否继续发布对象。 */
    override fun verified(
        id: Long,
        path: String,
        keyId: String,
        preview: ByteArray?,
        previewBytes: Int?
    ): Int = mapper.markSecurityVerified(id, path, keyId, preview, previewBytes)

    /** 原子登记内容审核不支持。 */
    override fun unsupported(id: Long): Int = mapper.markContentUnsupported(id)

    /** 原子登记内容审核拒绝。 */
    override fun rejected(id: Long): Int = mapper.markContentRejected(id)

    /** 原子登记恶意文件检测结果。 */
    override fun infected(id: Long): Int = mapper.markMalwareInfected(id)

    /** 截断内部失败码后原子登记扫描不可用，避免外部响应污染持久化字段。 */
    override fun unavailable(id: Long, code: String): Int = mapper.markScanUnavailable(id, code.take(MAX_FAILURE_CODE_LENGTH))

    private companion object {
        const val MAX_SCAN_BATCH_SIZE = 100
        const val MAX_FAILURE_CODE_LENGTH = 50
    }
}
