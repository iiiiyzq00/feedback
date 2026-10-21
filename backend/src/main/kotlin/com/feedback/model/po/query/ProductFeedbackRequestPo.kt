package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ProductFeedbackRequestPo(
    var id: Long = 0, var requestNo: String = "", var requestType: String = "", var title: String = "",
    var description: String = "", var sourceChannel: String? = null, var feedbackSourceId: Long? = null,
    var pagePath: String = "", var pageContext: String? = null, var attachments: String? = null,
    var requestStatus: String = "OPEN", var priorityCode: String = "P2", var plannedVersion: String? = null,
    var resolutionNote: String? = null, var createdBy: Long = 0, var handlerUserId: Long? = null,
    var acceptedAt: LocalDateTime? = null, var completedAt: LocalDateTime? = null,
    var createdAt: LocalDateTime? = null, var updatedAt: LocalDateTime? = null
)

