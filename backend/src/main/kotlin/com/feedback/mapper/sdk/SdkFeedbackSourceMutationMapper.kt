package com.feedback.mapper.sdk

import com.feedback.model.po.sdk.SdkFeedbackSourcePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** SDK 来源事实表 Mapper；支持业务记录定位、插入和仅接受更新事件的条件更新。 */
@Mapper
interface SdkFeedbackSourceMutationMapper {
    /** 按受控条件查询反馈来源；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findById(@Param("id") id: Long): SdkFeedbackSourcePo?
    /** 按受控条件查询反馈来源；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findByRecordId(@Param("sourceRecordId") sourceRecordId: String): SdkFeedbackSourcePo?
    /** 插入已校验的反馈来源；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insert(row: SdkFeedbackSourcePo): Int
    /** 以条件更新推进反馈来源；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun updateNewerEvent(row: SdkFeedbackSourcePo): Int
}
