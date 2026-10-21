package com.feedback.service.sdk

import com.feedback.dto.sdk.SdkTelemetryRequest

/** SDK 诊断事件写入服务契约；一次请求中的有界事件批次原子提交。 */
interface SdkTelemetryService {
    /** 在事务外完成裁剪与 JSON 组装，再交给短事务批量写入器。 */
    fun ingestTelemetry(request: SdkTelemetryRequest, appKey: String, clientIp: String)
}
