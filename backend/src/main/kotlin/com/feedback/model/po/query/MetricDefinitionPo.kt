package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MetricDefinitionPo(
    var metricCode: String? = null, var displayName: String? = null, var ownerModule: String? = null,
    var entityType: String? = null, var distinctKey: String? = null, var timeField: String? = null,
    var defaultWindow: String? = null, var definition: String? = null, var drilldownPath: String? = null,
    var metricVersion: String? = null, var updatedAt: LocalDateTime? = null
)

