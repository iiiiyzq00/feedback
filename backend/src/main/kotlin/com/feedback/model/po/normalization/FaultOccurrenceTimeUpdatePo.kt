package com.feedback.model.po.normalization

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class FaultOccurrenceTimeUpdatePo(
    val sourceId: Long,
    val occurredAt: LocalDateTime?,
    val raw: String?,
    val precision: String?,
    val source: String,
    val resolution: String
)

