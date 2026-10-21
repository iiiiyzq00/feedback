package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class InAppDispositionPo(
    var id: Long = 0,
    var feedbackId: Long = 0,
    var actorId: Long = 0,
    var eventType: String = "",
    var oldStatus: String? = null,
    var newStatus: String? = null,
    var reason: String? = null,
    var createdAt: LocalDateTime = LocalDateTime.MIN,
)

