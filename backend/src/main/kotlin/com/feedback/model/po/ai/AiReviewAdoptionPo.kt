package com.feedback.model.po.ai

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AiReviewAdoptionPo(val sourceId: Long, val category: String, val severity: String)

