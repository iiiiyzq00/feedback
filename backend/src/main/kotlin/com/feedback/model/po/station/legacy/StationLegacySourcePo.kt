package com.feedback.model.po.station.legacy

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/** StationLegacySourcePo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationLegacySourcePo(
    var id: Long = 0,
    var sourceRecordId: String = "",
    var appVersion: String? = null,
    var sourceEventTime: LocalDateTime = LocalDateTime.now(),
    var faultOccurredAt: LocalDateTime? = null,
    var faultTimeRaw: String? = null,
    var faultTimePrecision: String? = null,
    var faultTimeSource: String? = null,
    var faultTimeResolution: String = "MISSING",
    var sourceUpdateTime: LocalDateTime = LocalDateTime.now(),
    var ingestTime: LocalDateTime = LocalDateTime.now(),
    var publicId: ByteArray = ByteArray(0),
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
