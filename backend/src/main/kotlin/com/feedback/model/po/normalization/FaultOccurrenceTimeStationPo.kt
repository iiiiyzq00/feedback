package com.feedback.model.po.normalization

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class FaultOccurrenceTimeStationPo(var sourceId: Long = 0, var sourceDescription: String? = null)

