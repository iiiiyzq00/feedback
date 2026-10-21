package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class ReportDeletePo(var id: Long = 0, var type: String = "", var title: String = "", var scopeSnapshot: String? = null)

