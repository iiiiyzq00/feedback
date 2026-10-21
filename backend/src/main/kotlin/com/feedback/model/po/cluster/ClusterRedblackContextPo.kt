package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterRedblackContextPo(var sourceId: Long = 0, var sourcePlatform: String? = null)

