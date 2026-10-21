package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IncidentLifecycleStatePo(var incidentStatus: String = "", var lifecycleVersion: Long = 0, var incidentCommander: Long? = null)

