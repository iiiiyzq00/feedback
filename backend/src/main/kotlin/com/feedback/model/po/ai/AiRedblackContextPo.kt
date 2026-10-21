package com.feedback.model.po.ai

import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AiRedblackContextPo(var sourceId: Long = 0, var sourcePlatform: String? = null, var emotionTypeRaw: String? = null, var originMessage: String? = null)

