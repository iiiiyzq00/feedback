package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class InAppHandoffPo(
    var id: Long = 0,
    var feedbackId: Long = 0,
    var actorId: Long = 0,
    var reason: String = "",
    var createdAt: LocalDateTime = LocalDateTime.MIN,
)

