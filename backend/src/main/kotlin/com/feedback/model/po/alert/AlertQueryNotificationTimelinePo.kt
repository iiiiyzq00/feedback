package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryNotificationTimelinePo(var id:Long=0,var createdAt:LocalDateTime=LocalDateTime.MIN,var channel:String="",var status:String="",var acknowledgedAt:LocalDateTime?=null,var escalationTargetId:Long?=null)

