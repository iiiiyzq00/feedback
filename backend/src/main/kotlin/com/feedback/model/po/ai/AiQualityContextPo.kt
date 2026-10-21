package com.feedback.model.po.ai

import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AiQualityContextPo(var sourceId: Long = 0, var applicationTypeRaw: String? = null, var exchangeRequest: String? = null, var returnReason: String? = null)

