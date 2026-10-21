package com.feedback.model.po.sdk

/** SdkTransportTelemetryPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class SdkTransportTelemetryPo(
    var id: Long = 0,
    var targetId: String? = null,
    var detailJson: String = "{}",
    var ipAddress: String? = null
)
