package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class InAppAlertNotificationPo(
    var id: Long = 0,
    var alertInstanceId: Long? = null,
    var reportId: Long? = null,
    var ruleCode: String? = null,
    var content: String? = null,
    var eventType: String? = null,
    var createdAt: LocalDateTime = LocalDateTime.MIN,
    var deliveredAt: LocalDateTime? = null,
)

