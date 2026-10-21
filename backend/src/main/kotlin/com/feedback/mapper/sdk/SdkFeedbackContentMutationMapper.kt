package com.feedback.mapper.sdk

import com.feedback.model.po.sdk.SdkFeedbackContentPo
import org.apache.ibatis.annotations.Mapper

/** SDK 反馈正文物化表 Mapper；通过参数化 upsert 保存已脱敏正文状态。 */
@Mapper
interface SdkFeedbackContentMutationMapper {
    /** 按唯一业务键幂等写入反馈正文；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun upsert(row: SdkFeedbackContentPo): Int
}
