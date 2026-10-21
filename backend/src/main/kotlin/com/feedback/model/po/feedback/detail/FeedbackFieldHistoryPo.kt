package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackFieldHistoryPo(
    var id: Long = 0, var operationDetail: String? = null, var userId: Long? = null,
    var eventTime: LocalDateTime? = null, var createdAt: LocalDateTime? = null
)

