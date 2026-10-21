package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterAiPo(
    var sourceId: Long = 0,
    var aiSummary: String? = null,
    var aiConfidence: BigDecimal? = null,
    var reviewStatus: String = "PENDING"
)

