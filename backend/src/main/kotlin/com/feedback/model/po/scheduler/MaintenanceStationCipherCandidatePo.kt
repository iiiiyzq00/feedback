package com.feedback.model.po.scheduler

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class MaintenanceStationCipherCandidatePo(
    var id: Long = 0,
    var sourceId: Long = 0,
)

