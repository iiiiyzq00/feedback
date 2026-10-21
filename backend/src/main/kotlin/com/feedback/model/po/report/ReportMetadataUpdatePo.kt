package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ReportMetadataUpdatePo(
    var id: Long = 0,
    var promptSha256: String? = null,
    var modelConfig: String = "{}",
    var providerRequestId: String? = null,
    var inputTokens: Int? = null,
    var outputTokens: Int? = null,
    var latencyMs: Long? = null,
    var estimatedCostUsd: BigDecimal? = null,
    var freshnessStatus: String? = null,
    var freshnessJson: String = "{}",
)

