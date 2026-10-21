package com.feedback.model.po.normalization

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class FaultOccurrenceTimeSourcePo(
    var id: Long = 0,
    var sourceChannel: String = "",
    var sourceEventTime: LocalDateTime? = null
)

