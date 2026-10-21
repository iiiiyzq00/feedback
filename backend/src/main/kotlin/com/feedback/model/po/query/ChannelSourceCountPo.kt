package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ChannelSourceCountPo(var sourceChannel: String = "", var total: Long = 0, var latestDataAt: LocalDateTime? = null)

