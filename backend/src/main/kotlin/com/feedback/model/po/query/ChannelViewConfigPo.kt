package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ChannelViewConfigPo(
    var id: Long = 0, var configVersion: Long = 0, var status: String = "", var fieldsJson: String = "[]",
    var reason: String? = null, var createdAt: LocalDateTime? = null, var publishedAt: LocalDateTime? = null
)

