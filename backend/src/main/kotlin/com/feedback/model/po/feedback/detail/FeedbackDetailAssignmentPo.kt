package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackDetailAssignmentPo(
    var feedbackSourceId: Long = 0, var ownerUserId: Long? = null,
    var assignmentVersion: Long? = null, var assignedAt: LocalDateTime? = null
)

