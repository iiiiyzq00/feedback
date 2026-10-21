package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackNavigationContentPo(
    var sourceId: Long = 0, var functionModule: String? = null, var maskingStatus: String = "",
    var deleted: Boolean = false
)

