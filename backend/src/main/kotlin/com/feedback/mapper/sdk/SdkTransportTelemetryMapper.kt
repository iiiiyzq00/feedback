package com.feedback.mapper.sdk

import com.feedback.model.po.sdk.SdkTransportTelemetryPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** SDK 传输遥测表 Mapper；支持单条与最多一百条的参数化批量写入。 */
@Mapper
interface SdkTransportTelemetryMapper {
    /** 插入已校验的传输遥测；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insertTransportEvent(row: SdkTransportTelemetryPo): Int
    /** 插入已校验的传输遥测；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insertTransportEvents(@Param("rows") rows: List<SdkTransportTelemetryPo>): Int
}
