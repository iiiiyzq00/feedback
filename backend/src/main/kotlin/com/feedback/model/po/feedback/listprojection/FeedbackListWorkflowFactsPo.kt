package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackListWorkflowFactsPo(
    var feedbackSourceId: Long = 0,
    var processingStatus: String = "NEW",
    var distributionStatus: String = "UNASSIGNED",
    var updatedAt: LocalDateTime? = null
)

