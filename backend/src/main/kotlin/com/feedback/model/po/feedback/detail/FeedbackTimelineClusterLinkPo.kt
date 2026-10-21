package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackTimelineClusterLinkPo(
    var id: Long = 0, var clusterId: Long = 0, var createdAt: LocalDateTime? = null,
    var reviewStatus: String? = null, var decisionReason: String? = null,
    var decisionVersion: String? = null, var matchSimilarity: BigDecimal? = null
)

