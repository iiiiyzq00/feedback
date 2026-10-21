package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ReportFactSourcePo(
    var id: Long = 0,
    var channel: String = "",
    var packageName: String? = null,
    var appVersion: String? = null,
    var sourceEventTime: LocalDateTime? = null,
    var occurredAt: LocalDateTime? = null,
    var provinceCode: String? = null,
    var cityCode: String? = null,
)

