package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterContentPo(
    var sourceId: Long = 0,
    var title: String? = null,
    var description: String? = null,
    var functionModule: String? = null,
    var problemType: String? = null,
    var usageStage: String? = null,
    var severityLevel: String? = null,
    var issueCategory: String? = null,
    var maskingStatus: String = "PENDING",
    var isDeleted: Boolean = false,
    var createdAt: LocalDateTime = LocalDateTime.MIN,
    var updatedAt: LocalDateTime = LocalDateTime.MIN
)

