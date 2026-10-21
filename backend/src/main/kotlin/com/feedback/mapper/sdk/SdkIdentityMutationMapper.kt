package com.feedback.mapper.sdk

import com.feedback.model.po.sdk.SdkIdentityPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** SDK 来源身份表 Mapper；查询与批量写入均要求调用方提供去重且有界的集合。 */
@Mapper
interface SdkIdentityMutationMapper {
    /** 按受控条件查询来源身份；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<SdkIdentityPo>
    /** 在显式批量上限内幂等写入来源身份；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun upsertBatch(@Param("rows") rows: List<SdkIdentityPo>): Int
}
