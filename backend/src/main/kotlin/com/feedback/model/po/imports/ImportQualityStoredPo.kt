package com.feedback.model.po.imports

import java.math.BigDecimal
import java.time.LocalDateTime

data class ImportQualityStoredPo(
    val metricVersion: String, val totalRows: Int, val completedRows: Int,
    val completenessRate: BigDecimal?, val mappingSuccessRate: BigDecimal?, val maskingPassRate: BigDecimal?,
    val duplicateRate: BigDecimal?, val invalidRate: BigDecimal?, val timeValidRate: BigDecimal?,
    val enumValidRate: BigDecimal?, val appMappingRate: BigDecimal?, val moduleMappingRate: BigDecimal?,
    val versionMappingRate: BigDecimal?, val importSuccessRate: BigDecimal?,
    val crossChannelSuspectedDuplicateRate: BigDecimal?, val anomalyCount: Int,
    val quarantineCount: Int, val attachmentAvailableRate: BigDecimal?, val qualityScore: BigDecimal?,
    val calculatedAt: LocalDateTime?
)
