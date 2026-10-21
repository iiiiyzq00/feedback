package com.feedback.repository.attachment

import com.feedback.model.attachment.*

import com.feedback.model.po.attachment.FeedbackAttachmentPo

/**
 * 附件写入与去重查询仓储。
 *
 * 接口隔离上层业务与 MyBatis；每个写方法是一条原子 SQL，不自行扩大事务范围。
 */
interface FeedbackAttachmentMutationRepository {
    /** 插入附件并返回带数据库主键的记录；插入失败或主键缺失时失败关闭。 */
    fun create(row: FeedbackAttachmentPo): FeedbackAttachmentPo

    /** 按主键读取附件持久化快照，不执行文件 I/O。 */
    fun find(id: Long): FeedbackAttachmentPo?

    /** 按外部来源种类与来源附件标识读取幂等记录。 */
    fun findSourceIdentity(kind: String, id: String): FeedbackAttachmentPo?

    /** 按反馈、内容哈希和来源种类读取去重记录。 */
    fun findSourceHash(sourceId: Long, hash: ByteArray, kind: String): FeedbackAttachmentPo?

    /** 原子更新已落盘信息；更新失败时抛错，避免业务误判成功。 */
    fun updateStored(row: FeedbackAttachmentPo)

    /** 将 SDK 暂存附件原子关联到反馈；返回受影响行数供调用方处理竞争。 */
    fun linkToSource(sdkIngestId: Long, sourceId: Long): Int

    /** 批量返回已登记路径；空集合不访问数据库，调用方必须传入有界集合。 */
    fun registeredStoragePaths(storagePaths: Collection<String>): Set<String>
}
