package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterReviewQueuePo(
    var feedbackSourceId: Long = 0,
    var priority: String = "",
    var dueAt: LocalDateTime = LocalDateTime.MIN,
    var createdAt: LocalDateTime = LocalDateTime.MIN
)

