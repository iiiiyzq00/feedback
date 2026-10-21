package com.feedback.repository.impl.attachment

import com.feedback.application.port.feedback.attachment.AttachmentGrantFingerprint
import com.feedback.application.port.feedback.attachment.CreateAttachmentGrantCommand
import com.feedback.common.AttachmentGrantPurpose
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.attachment.AttachmentGrantMapper
import com.feedback.model.po.attachment.AttachmentGrantInsertPo
import com.feedback.repository.attachment.AttachmentGrantRepository
import org.springframework.stereotype.Repository

/**
 * 基于 MyBatis 的一次性附件授权仓储实现。
 *
 * 不保存原始令牌；单条插入和条件消费依赖数据库原子性，行锁由上层短事务统一持有。
 */
@Repository
class MyBatisAttachmentGrantRepositoryImpl(
    private val mapper: AttachmentGrantMapper
) : AttachmentGrantRepository {
    /** 插入唯一授权指纹；受影响行数异常时抛错，使外层授权与审计事务回滚。 */
    override fun create(command: CreateAttachmentGrantCommand) {
        val inserted = mapper.insert(
            AttachmentGrantInsertPo(
                sourceId = command.sourceId,
                attachmentId = command.attachmentId,
                userId = command.userId,
                fingerprint = command.fingerprint.databaseValue(),
                expiresAt = command.expiresAt,
                accessReason = command.accessReason
            )
        )
        if (inserted != 1) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "ATTACHMENT_GRANT_CREATE_FAILED")
        }
    }

    /** 在现有事务中执行 `FOR UPDATE`，仅返回用途、主体、时效和撤销状态均匹配的授权。 */
    override fun lockConsumableId(
        fingerprint: AttachmentGrantFingerprint,
        attachmentId: Long,
        sourceId: Long,
        userId: Long,
        purpose: AttachmentGrantPurpose
    ): Long? = mapper.lockConsumableId(
        fingerprint.databaseValue(),
        attachmentId,
        sourceId,
        userId,
        purpose.name
    )

    /** 仅在授权仍未消费且未撤销时原子写入消费时间；返回 false 表示并发或状态冲突。 */
    override fun markConsumed(grantId: Long): Boolean = mapper.markConsumed(grantId) == 1
}
