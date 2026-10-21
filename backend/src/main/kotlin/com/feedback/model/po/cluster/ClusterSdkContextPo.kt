package com.feedback.model.po.cluster

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class ClusterSdkContextPo(
    var sourceId: Long = 0,
    var packageName: String? = null,
    var appId: String? = null,
    var osVersion: String? = null,
    var deviceModel: String? = null
)

