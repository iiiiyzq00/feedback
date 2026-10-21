package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackDetailWorkflowPo(
    var feedbackSourceId: Long = 0, var processingStatus: String? = null,
    var distributionStatus: String? = null, var statusMappingVersion: String? = null,
    var statusMappingResult: String? = null, var statusMappedAt: LocalDateTime? = null
)

