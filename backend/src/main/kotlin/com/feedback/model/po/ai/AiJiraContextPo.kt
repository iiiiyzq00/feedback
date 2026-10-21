package com.feedback.model.po.ai

import com.feedback.model.feedback.FeedbackAi
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AiJiraContextPo(var sourceId: Long = 0, var issueTypeRaw: String? = null, var priorityRaw: String? = null, var environment: String? = null)

