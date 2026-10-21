package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ReportDetailPo(
    var id: Long = 0,
    var taskId: Long? = null,
    var type: String = "",
    var title: String = "",
    var periodStart: LocalDate? = null,
    var periodEnd: LocalDate? = null,
    var status: String = "",
    var generatedAt: LocalDateTime? = null,
    var dataLatestTime: LocalDateTime? = null,
    var freshnessStatus: String? = null,
    var freshnessJson: String? = null,
    var rawRecordCount: Long = 0,
    var uniqueProblemCount: Long = 0,
    var generationSource: String? = null,
    var contentJson: String? = null,
    var factsJson: String? = null,
    var generatedSummary: String? = null,
    var validationStatus: String? = null,
    var validationErrors: String? = null,
    var promptVersion: String? = null,
    var promptSha256: String? = null,
    var skillVersion: String? = null,
    var modelName: String? = null,
    var modelConfig: String? = null,
    var providerRequestId: String? = null,
    var inputTokens: Int? = null,
    var outputTokens: Int? = null,
    var latencyMs: Long? = null,
    var estimatedCostUsd: BigDecimal? = null,
    var degraded: Boolean = false,
    var scopeSnapshot: String? = null,
)

