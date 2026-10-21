package com.feedback.model.po.scheduler

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDate

data class ReportScheduleRunInsertPo(
    var key: String = "",
    var type: String = "",
    var periodStart: LocalDate? = null,
    var periodEnd: LocalDate? = null,
    var id: Long? = null,
)

