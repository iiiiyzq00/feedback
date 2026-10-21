package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackTimelineImportRowPo(
    var id: Long = 0, var feedbackSourceId: Long = 0, var sheetName: String? = null, var rowNumber: Int? = null
)

