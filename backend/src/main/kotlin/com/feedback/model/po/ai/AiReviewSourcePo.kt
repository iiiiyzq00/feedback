package com.feedback.model.po.ai

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AiReviewSourcePo(
    var id: Long = 0, var sourceChannel: String = "", var provinceCode: String? = null, var cityCode: String? = null
)

