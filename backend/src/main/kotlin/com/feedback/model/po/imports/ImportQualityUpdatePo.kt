package com.feedback.model.po.imports

import java.math.BigDecimal
import java.time.LocalDateTime

data class ImportQualityUpdatePo(
    val batchId: Long, val version: String, val total: Int, val completed: Int,
    val completeness: BigDecimal, val mapping: BigDecimal, val masking: BigDecimal,
    val duplicate: BigDecimal, val invalid: BigDecimal, val timeValid: BigDecimal,
    val enumValid: BigDecimal, val appMapping: BigDecimal, val moduleMapping: BigDecimal,
    val versionMapping: BigDecimal, val importSuccess: BigDecimal, val crossDuplicate: BigDecimal,
    val anomalies: Int, val quarantine: Int, val attachment: BigDecimal?, val score: BigDecimal,
    val detailJson: String, val calculatedAt: LocalDateTime
)
