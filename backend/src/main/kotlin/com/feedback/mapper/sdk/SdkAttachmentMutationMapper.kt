package com.feedback.mapper.sdk

import com.feedback.model.po.sdk.SdkAttachmentIngressPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** SDK 附件接入表的参数化单表 Mapper；仅供附件 Repository 实现调用。 */
@Mapper
interface SdkAttachmentMutationMapper {
    /** 按受控条件查询附件记录；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findByClientAttachmentId(
        @Param("ingestId") ingestId: Long,
        @Param("clientAttachmentId") clientAttachmentId: String
    ): SdkAttachmentIngressPo?
    /** 插入已校验的附件记录；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insertIngress(row: SdkAttachmentIngressPo): Int
    /** 建立已校验的关联：附件记录；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun linkToSource(@Param("ingestId") ingestId: Long, @Param("sourceId") sourceId: Long): Int
}
