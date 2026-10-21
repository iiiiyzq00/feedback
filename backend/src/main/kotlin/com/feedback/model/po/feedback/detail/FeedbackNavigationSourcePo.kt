package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackNavigationSourcePo(
    var id: Long = 0, var sourceChannel: String = "", var provinceCode: String? = null,
    var cityCode: String? = null, var faultOccurredAt: LocalDateTime? = null
)

