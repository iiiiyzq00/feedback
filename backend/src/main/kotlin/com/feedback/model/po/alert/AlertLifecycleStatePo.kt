package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertLifecycleStatePo(var instanceStatus: String = "", var lifecycleVersion: Long = 0, var instanceSeverity: String = "")

