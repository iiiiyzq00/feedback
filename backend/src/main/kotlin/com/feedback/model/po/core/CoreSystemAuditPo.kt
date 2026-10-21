package com.feedback.model.po.core

import com.feedback.model.sdk.ChannelSdk
import com.feedback.model.feedback.FeedbackIdentity
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class CoreSystemAuditPo(
    var id: Long? = null, var userId: Long? = null, var operation: String = "",
    var targetType: String = "", var targetId: String? = null, var operationDetailJson: String = "{}",
    var ipAddress: String? = null, var createdAt: java.time.LocalDateTime = java.time.LocalDateTime.now()
)

