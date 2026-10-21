package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertEscalationMarkerPo(var alertInstanceId:Long=0,var notifyContent:String?=null)

