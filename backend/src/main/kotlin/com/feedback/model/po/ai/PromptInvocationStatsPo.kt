package com.feedback.model.po.ai

import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class PromptInvocationStatsPo(
    var skillCode: String = "", var promptVersion: String = "", var total: Int = 0, var failures: Int = 0
)

