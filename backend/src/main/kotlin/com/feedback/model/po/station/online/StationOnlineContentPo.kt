package com.feedback.model.po.station.online

import java.time.LocalDateTime

/** StationOnlineContentPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationOnlineContentPo(
    var sourceId: Long = 0,
    var title: String? = null,
    var description: String? = null,
    var mappingRuleVersion: String? = null,
    var normalizationVersion: String? = null,
    var processingStatus: String = "OPEN",
    var maskingStatus: String = "PENDING",
    var isDeleted: Boolean = false
)
