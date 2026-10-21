package com.feedback.model.po.sdk

import java.time.LocalDateTime

/** SdkFeedbackSourcePo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class SdkFeedbackSourcePo(
    var id: Long = 0,
    var publicId: ByteArray = ByteArray(0),
    var sourceChannel: String = "SDK",
    var sourceRecordId: String = "",
    var appVersion: String? = null,
    var packageName: String? = null,
    var sourceEventTime: LocalDateTime? = null,
    var faultOccurredAt: LocalDateTime? = null,
    var faultTimeRaw: String? = null,
    var faultTimePrecision: String? = null,
    var faultTimeSource: String? = null,
    var faultTimeResolution: String = "RESOLVED",
    var sourceUpdateTime: LocalDateTime = LocalDateTime.now(),
    var ingestTime: LocalDateTime = LocalDateTime.now(),
    var provinceCode: String? = null,
    var cityCode: String? = null,
    var districtCode: String? = null,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
