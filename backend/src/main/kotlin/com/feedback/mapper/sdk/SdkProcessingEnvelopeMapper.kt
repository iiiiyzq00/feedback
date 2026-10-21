package com.feedback.mapper.sdk

import com.feedback.model.po.sdk.SdkProcessingEnvelopePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** SDK 处理信封表 Mapper；保持业务键、回执定位及处理状态推进的单表契约。 */
@Mapper
interface SdkProcessingEnvelopeMapper {
    /** 按受控条件查询处理信封；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findById(@Param("id") id: Long): SdkProcessingEnvelopePo?
    /** 按受控条件查询处理信封；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findByBusinessKey(@Param("appId") appId: String, @Param("clientFeedbackId") clientFeedbackId: String): SdkProcessingEnvelopePo?
    /** 按受控条件查询处理信封；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findByReceiptId(@Param("receiptId") receiptId: ByteArray): SdkProcessingEnvelopePo?
    /** 按上限查询待处理的处理信封；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findPending(@Param("limit") limit: Int): List<SdkProcessingEnvelopePo>
    /** 按受控条件查询处理信封；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<SdkProcessingEnvelopePo>
    /** 插入已校验的处理信封；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insert(row: SdkProcessingEnvelopePo): Int
    /** 以条件更新推进处理信封；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun updateProcessingState(envelope: SdkProcessingEnvelopePo): Int
}
