package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MetricContentFactPo(var sourceId: Long = 0, var maskingStatus: String = "")

