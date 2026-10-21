package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ReportChannelFreshnessPo(var sourceChannel: String = "", var latest: LocalDateTime? = null)

