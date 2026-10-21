package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ReportTaskLeasePo(
    var cancelRequested: Boolean = false,
    var status: String = "",
    var leaseHolder: String? = null,
)

