package com.feedback.model.po.station.legacy

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/** StationLegacyChannelEnrichmentPo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationLegacyChannelEnrichmentPo(
    val sourceId: Long,
    val productId: String,
    val sourceProductName: String?,
    val sourceAppId: String?,
    val sourceAppName: String?,
    val sourceUserId: String?
)
