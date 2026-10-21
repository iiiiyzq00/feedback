package com.feedback.model.po.knowledge

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class KnowledgeUsageAggregatePo(
    var shownCount: Long = 0,
    var adoptionCount: Long = 0,
    var effectiveCount: Long = 0,
    var ineffectiveCount: Long = 0,
    var lastUsedAt: LocalDateTime? = null
)

