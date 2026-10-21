package com.feedback.model.po.station.online

import java.time.LocalDateTime

/** StationOperationsStatusCountPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationOperationsStatusCountPo(val status: String, val count: Long)
