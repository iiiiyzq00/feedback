package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterEvaluationPo(
    var id: Long = 0,
    var taskStatus: String = "",
    var modelNameSnapshot: String? = null,
    var outputSnapshot: String? = null,
    var completedAt: LocalDateTime? = null
)

