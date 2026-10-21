package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterQualityContextPo(var sourceId: Long = 0, var applicationTypeRaw: String? = null)

