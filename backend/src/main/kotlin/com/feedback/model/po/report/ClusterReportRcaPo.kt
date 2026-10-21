package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ClusterReportRcaPo(
    var id: Long = 0,
    var incidentId: Long? = null,
    var title: String? = null,
    var rcaType: String? = null,
    var triggerType: String? = null,
    var rootCause: String? = null,
    var solution: String? = null,
    var status: String? = null,
    var confidence: Double? = null,
    var createdAt: LocalDateTime? = null,
)

