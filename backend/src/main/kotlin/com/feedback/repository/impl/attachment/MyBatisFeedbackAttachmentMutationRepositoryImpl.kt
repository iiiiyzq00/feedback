package com.feedback.repository.impl.attachment

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.attachment.FeedbackAttachmentMapper
import com.feedback.model.po.attachment.FeedbackAttachmentPo
import com.feedback.repository.attachment.FeedbackAttachmentMutationRepository
import org.springframework.stereotype.Repository

/**
 * 基于 MyBatis 的附件写入仓储实现。
 *
 * 只执行数据库访问，不读取或写入文件；单条原子 SQL 不额外声明事务，多步一致性由 ServiceImpl 编排。
 */
@Repository
class MyBatisFeedbackAttachmentMutationRepositoryImpl(
    private val mapper: FeedbackAttachmentMapper
) : FeedbackAttachmentMutationRepository {
    /** 插入并校验数据库回填主键，任何异常都以服务错误失败关闭。 */
    override fun create(row: FeedbackAttachmentPo): FeedbackAttachmentPo {
        if (mapper.insert(row) != 1) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "ATTACHMENT_INSERT_FAILED")
        }
        if (row.id <= 0) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "ATTACHMENT_ID_MISSING")
        }
        return row
    }

    /** 按主键返回数据库快照。 */
    override fun find(id: Long): FeedbackAttachmentPo? = mapper.findById(id)

    /** 按稳定来源编码和外部标识返回幂等快照。 */
    override fun findSourceIdentity(kind: String, id: String): FeedbackAttachmentPo? =
        mapper.findBySourceKindAndSourceAttachmentId(kind, id)

    /** 按反馈、哈希和稳定来源编码返回去重快照。 */
    override fun findSourceHash(sourceId: Long, hash: ByteArray, kind: String): FeedbackAttachmentPo? =
        mapper.findBySourceHashKind(sourceId, hash, kind)

    /** 原子更新已存储字段，受影响行数不是一时抛错。 */
    override fun updateStored(row: FeedbackAttachmentPo) {
        if (mapper.updateStored(row) != 1) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "ATTACHMENT_UPDATE_FAILED")
        }
    }

    /** 原子建立 SDK 暂存记录与反馈的关联。 */
    override fun linkToSource(sdkIngestId: Long, sourceId: Long): Int = mapper.linkToSource(sdkIngestId, sourceId)

    /** 空集合直接返回；非空集合使用一条批量查询，禁止逐项 N+1。 */
    override fun registeredStoragePaths(storagePaths: Collection<String>): Set<String> {
        if (storagePaths.isEmpty()) {
            return emptySet()
        }
        return mapper.findRegisteredStoragePaths(storagePaths).toSet()
    }
}
