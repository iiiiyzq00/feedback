package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertEscalationNotificationInsertPo(val ruleId:Long,val ruleCode:String,val alertInstanceId:Long,val content:String)

