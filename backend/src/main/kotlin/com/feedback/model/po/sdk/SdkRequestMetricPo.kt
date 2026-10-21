package com.feedback.model.po.sdk

/** SdkRequestMetricPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class SdkRequestMetricPo(
    var id: Long = 0,
    var appId: String = "UNKNOWN",
    var httpStatus: Int = 500,
    var durationMs: Long = 0,
    var errorCode: String? = null,
    var resultType: String = "SERVER_ERROR",
    var snapshotJson: String = "{}"
)
