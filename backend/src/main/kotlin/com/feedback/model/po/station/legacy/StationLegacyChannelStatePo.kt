package com.feedback.model.po.station.legacy

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/** StationLegacyChannelStatePo 的数据库边界模型；字段名和稳定状态 code 与既有表结构保持兼容。 */
data class StationLegacyChannelStatePo(
    val sourceId: Long,
    val contactStatus: String,
    val pendingPhotoUrls: String?,
    val ingestStatus: String,
    val processingStage: String,
    val nextRetryAt: LocalDateTime?,
    val lastErrorCode: String?,
    val lastErrorSummary: String?,
    val processedAt: LocalDateTime?
)
