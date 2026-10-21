package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ReportActorPo(
    var id: Long = 0,
    var username: String = "",
    var displayName: String? = null,
    var authVersion: Long = 0,
)

