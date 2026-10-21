package com.feedback.model.po.scheduler

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class MaintenanceMaskingStatusPo(
    var sourceId: Long = 0,
    var maskingStatus: String = "",
)

