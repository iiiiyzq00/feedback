package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class LifecycleTimelinePo(
    var id: Long = 0,
    var fromStatus: String = "",
    var toStatus: String = "",
    var version: Long = 0,
    var reason: String = "",
    var evidence: String? = null,
    var changedBy: Long? = null,
    var changedAt: LocalDateTime = LocalDateTime.MIN,
)

