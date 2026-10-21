package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ChannelAcceptancePo(
    var id: Long = 0, var sourceChannel: String = "", var status: String = "", var comment: String? = null,
    var acceptedAt: LocalDateTime? = null, var acceptedBy: Long = 0
)

