package com.feedback.model.po.station.legacy

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/** StationLegacyContentPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationLegacyContentPo(
    var sourceId: Long = 0,
    var title: String? = null,
    var description: String? = null,
    var problemType: String? = null,
    var mappingRuleVersion: String = "station-legacy-v1",
    var normalizationVersion: String = "station-norm-v1",
    var processingStatus: String = "OPEN",
    var maskingStatus: String = "PASS",
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
