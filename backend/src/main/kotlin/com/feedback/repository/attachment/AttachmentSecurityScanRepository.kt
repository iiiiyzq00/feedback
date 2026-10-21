package com.feedback.repository.attachment

import com.feedback.model.attachment.*

import com.feedback.model.po.attachment.AttachmentScanCandidatePo

/**
 * 附件安全扫描状态仓储。
 *
 * 仅负责有界候选读取和单条条件状态更新；ClamAV、文件、加密及对象存储 I/O 均在仓储外执行。
 */
interface AttachmentSecurityScanRepository {
    /** 返回最多一百条待扫描记录，调用方可在事务外并发处理。 */
    fun pending(limit: Int): List<AttachmentScanCandidatePo>

    /** 以条件更新登记验证后的密文及可选日志预览；返回受影响行数供调用方识别并发失效。 */
    fun verified(
        id: Long,
        path: String,
        keyId: String,
        preview: ByteArray? = null,
        previewBytes: Int? = null
    ): Int

    /** 将仍处于可迁移状态的附件原子标记为内容审核不支持。 */
    fun unsupported(id: Long): Int

    /** 将仍处于可迁移状态的附件原子标记为内容审核拒绝。 */
    fun rejected(id: Long): Int

    /** 将仍处于可迁移状态的附件原子标记为恶意文件。 */
    fun infected(id: Long): Int

    /** 记录有界失败码并保持失败关闭；未知或超长外部错误不得直接入库。 */
    fun unavailable(id: Long, code: String): Int
}
