package com.feedback.model.query.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ReportFactSourceQuery(
    val from: LocalDateTime?,
    val to: LocalDateTime?,
    val channels: Set<String>,
    val regions: Set<String>?,
    val packageName: String? = null,
    val appVersion: String? = null,
    val afterId: Long = 0,
    val limit: Int = 500,
)

