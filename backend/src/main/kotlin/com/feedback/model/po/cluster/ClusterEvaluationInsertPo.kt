package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterEvaluationInsertPo(
    val taskKey: String,
    val taskStatus: String,
    val inputSnapshot: String,
    val outputSnapshot: String,
    val actor: Long?,
    val model: String
)

