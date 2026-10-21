package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryInstancePo(var id:Long=0,var ruleId:Long=0,var feedbackSourceId:Long?=null,var clusterId:Long?=null,var level:String="",var status:String="",var lifecycleVersion:Long=0,var statusReason:String?=null,var triggeredAt:LocalDateTime=LocalDateTime.MIN,var resolvedAt:LocalDateTime?=null)

