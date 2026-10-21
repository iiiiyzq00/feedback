package com.feedback.model.po.ai

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class PromptFeedbackStatsPo(
    var promptVersion: String = "",
    var avgConfidence: BigDecimal? = null,
    var correctionRate: BigDecimal? = null,
)

