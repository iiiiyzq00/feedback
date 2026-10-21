package com.feedback.model.po.station.online

import java.time.LocalDateTime

/** StationOnlineSourcePo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationOnlineSourcePo(
    var id: Long = 0,
    var publicId: ByteArray = ByteArray(0),
    var sourceRecordId: String = "",
    var appVersion: String? = null,
    var sourceEventTime: LocalDateTime = LocalDateTime.now(),
    var faultOccurredAt: LocalDateTime? = null,
    var faultTimeRaw: String? = null,
    var faultTimePrecision: String? = null,
    var faultTimeSource: String? = null,
    var faultTimeResolution: String = "MISSING",
    var sourceUpdateTime: LocalDateTime = LocalDateTime.now(),
    var ingestTime: LocalDateTime = LocalDateTime.now()
)
