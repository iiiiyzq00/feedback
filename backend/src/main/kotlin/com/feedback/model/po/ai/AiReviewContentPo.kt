package com.feedback.model.po.ai

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AiReviewContentPo(
    var sourceId: Long = 0, var title: String? = null, var description: String? = null,
    var functionModule: String? = null, var issueCategory: String? = null, var severityLevel: String? = null
)

