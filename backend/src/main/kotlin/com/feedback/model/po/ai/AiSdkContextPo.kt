package com.feedback.model.po.ai

import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AiSdkContextPo(var sourceId: Long = 0, var inputMethods: String? = null)

