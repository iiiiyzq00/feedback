package com.feedback.mapper.sdk

import com.feedback.model.po.sdk.SdkRequestMetricPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** SDK 请求指标表 Mapper；负责单条写入和带显式上限的保留期清理。 */
@Mapper
interface SdkRequestMetricMapper {
    /** 插入已校验的请求指标；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insertRequestMetric(row: SdkRequestMetricPo): Int
    /** 按保留期和批量上限清理请求指标；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun purgeExpiredRequestMetrics(@Param("limit") limit: Int): Int
}
