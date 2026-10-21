package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ReportListPo(
    var id: Long = 0,
    var type: String = "",
    var title: String = "",
    var periodStart: LocalDate? = null,
    var periodEnd: LocalDate? = null,
    var status: String = "",
    var generatedAt: LocalDateTime? = null,
    var dataLatestTime: LocalDateTime? = null,
    var freshnessStatus: String? = null,
    var rawRecordCount: Long = 0,
    var uniqueProblemCount: Long = 0,
    var generationSource: String? = null,
    var validationStatus: String? = null,
    var degraded: Boolean = false,
    var scopeSnapshot: String? = null,
    var overviewMetrics: String? = null,
)

