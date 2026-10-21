package com.feedback.model.po.imports

import java.time.LocalDateTime

data class ImportQualityBatchPo(val id: Long, val totalRows: Int, val sourceChannel: String, val createdAt: LocalDateTime)
