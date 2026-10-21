package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackClusterLinkSummaryPo(
    var id: Long = 0, var clusterId: Long = 0, var matchSimilarity: BigDecimal? = null,
    var decisionReason: String? = null, var decisionVersion: String? = null
)

