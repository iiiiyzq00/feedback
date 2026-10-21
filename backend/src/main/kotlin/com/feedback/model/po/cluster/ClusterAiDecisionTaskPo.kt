package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterAiDecisionTaskPo(
    var id: Long = 0,
    var taskStatus: String = "",
    var inputSnapshot: String? = null,
    var outputSnapshot: String? = null,
    var leaseHolder: String? = null
)

