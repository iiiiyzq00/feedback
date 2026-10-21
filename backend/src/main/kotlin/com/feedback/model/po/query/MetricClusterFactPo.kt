package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MetricClusterFactPo(var sourceId: Long = 0, var linkType: String = "")

