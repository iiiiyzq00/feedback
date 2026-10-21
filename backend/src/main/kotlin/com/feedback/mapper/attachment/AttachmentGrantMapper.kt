package com.feedback.mapper.attachment

import com.feedback.model.po.attachment.AttachmentGrantInsertPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 一次性附件授权 Mapper；锁定与条件消费必须处于同一事务。 */
@Mapper
interface AttachmentGrantMapper {
    /** 插入一次性授权指纹记录，原始令牌不得作为参数传入。 */
    fun insert(grant: AttachmentGrantInsertPo): Int

    /** 以完整绑定条件和有效状态执行 `FOR UPDATE`；调用方不得先无锁读取再消费。 */
    fun lockConsumableId(
        @Param("fingerprint") fingerprint: ByteArray,
        @Param("attachmentId") attachmentId: Long,
        @Param("sourceId") sourceId: Long,
        @Param("userId") userId: Long,
        @Param("purpose") purpose: String
    ): Long?

    /** CAS 式标记消费；返回 1 表示成功，0 表示状态已变化或授权已失效。 */
    fun markConsumed(@Param("grantId") grantId: Long): Int
}
