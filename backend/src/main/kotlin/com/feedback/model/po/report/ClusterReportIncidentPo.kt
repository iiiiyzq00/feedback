package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ClusterReportIncidentPo(
    var id: Long = 0,
    var level: String? = null,
    var title: String? = null,
    var status: String? = null,
    var triggeredAt: LocalDateTime? = null,
)

