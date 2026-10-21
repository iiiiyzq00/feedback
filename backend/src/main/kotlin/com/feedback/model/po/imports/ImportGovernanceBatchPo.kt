package com.feedback.model.po.imports

import java.time.LocalDateTime

data class ImportGovernanceBatchPo(
    val id: Long, val sourceChannel: String, val triggerType: String?, val sourceInstanceHash: ByteArray?,
    val mappingConfigVersion: String?, val syncWindowFrom: LocalDateTime?, val syncWindowTo: LocalDateTime?,
    val batchStatus: String, val reconciliationStatus: String, val governanceStatus: String, val totalRows: Int
)
