package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ReportTaskViewPo(
    var id: Long = 0,
    var publicId: String? = null,
    var reportType: String? = null,
    var generationSource: String? = null,
    var status: String = "",
    var cancelRequested: Boolean = false,
    var retryCount: Int = 0,
    var maxRetries: Int = 0,
    var reportId: Long? = null,
    var errorCode: String? = null,
    var errorDetail: String? = null,
    var createdAt: LocalDateTime? = null,
    var startedAt: LocalDateTime? = null,
    var completedAt: LocalDateTime? = null,
    var actorId: Long? = null,
)

