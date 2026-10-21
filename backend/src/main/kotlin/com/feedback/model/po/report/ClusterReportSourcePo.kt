package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ClusterReportSourcePo(
    var id: Long = 0,
    var channel: String = "",
    var occurredAt: LocalDateTime? = null,
    var province: String? = null,
    var city: String? = null,
)

