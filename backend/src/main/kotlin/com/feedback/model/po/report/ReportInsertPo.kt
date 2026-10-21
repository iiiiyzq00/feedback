package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ReportInsertPo(
    var publicId: String = "",
    var type: String = "",
    var title: String = "",
    var periodStart: LocalDate? = null,
    var periodEnd: LocalDate? = null,
    var dataLatestTime: LocalDateTime? = null,
    var rawRecordCount: Long = 0,
    var uniqueProblemCount: Long = 0,
    var contentJson: String = "{}",
    var factsJson: String = "{}",
    var summary: String? = null,
    var validationStatus: String = "",
    var validationErrors: String = "[]",
    var promptVersion: String = "",
    var skillVersion: String = "",
    var modelName: String? = null,
    var degraded: Boolean = false,
    var scopeSnapshot: String = "{}",
    var status: String = "",
    var source: String = "",
    var actor: Long? = null,
    var id: Long? = null,
)

