package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import java.time.LocalDateTime

data class AlertInstanceRuntimePo(var openAlerts:Long=0,var recovered24h:Long=0)

