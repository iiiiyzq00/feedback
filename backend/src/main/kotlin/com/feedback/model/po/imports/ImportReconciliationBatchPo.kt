package com.feedback.model.po.imports

import java.time.LocalDateTime

data class ImportReconciliationBatchPo(val id: Long, val sourceChannel: String, val batchStatus: String, val reconciliationStatus: String, val createdAt: LocalDateTime)
