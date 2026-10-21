package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ReportFactImportBatchPo(
    var id: Long = 0,
    var batchCode: String = "",
    var channel: String = "",
    var batchStatus: String = "",
    var reconciliationStatus: String? = null,
    var totalRows: Int = 0,
    var successRows: Int = 0,
    var insertedRows: Int = 0,
    var updatedRows: Int = 0,
    var skippedRows: Int = 0,
    var failedRows: Int = 0,
    var mappingVersion: String? = null,
    var dataDateFrom: LocalDate? = null,
    var dataDateTo: LocalDate? = null,
    var startedAt: LocalDateTime? = null,
    var completedAt: LocalDateTime? = null,
    var createdAt: LocalDateTime? = null,
)

