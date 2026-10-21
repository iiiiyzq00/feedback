package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ReportTaskDuePo(
    var id: Long = 0,
    var requestSnapshot: String = "{}",
    var generationSource: String = "",
    var actorId: Long? = null,
)

