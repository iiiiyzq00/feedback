package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class InAppProductEventPo(
    var id: Long = 0,
    var requestId: Long = 0,
    var actorId: Long = 0,
    var eventType: String = "",
    var reason: String = "",
    var newHandlerUserId: Long? = null,
    var createdAt: LocalDateTime = LocalDateTime.MIN,
)

