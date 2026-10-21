package com.feedback.model.po.normalization

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class FaultOccurrenceTimeQualityPo(
    var sourceId: Long = 0,
    var faultConfirmed: String? = null,
    var faultRemark: String? = null
)

