package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ReportTaskInsertPo(
    var publicId: String = "",
    var source: String = "",
    var actorId: Long? = null,
    var requestSnapshot: String = "{}",
    var id: Long? = null,
)

