package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class FeedbackDetailImportBatchPo(var id: Long = 0, var batchId: String? = null, var batchStatus: String? = null)

