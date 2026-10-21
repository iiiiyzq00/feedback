package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryIncidentPo(var id:Long=0,var title:String="",var status:String="",var severity:String="",var lifecycleVersion:Long=0,var commanderId:Long?=null,var openedAt:LocalDateTime=LocalDateTime.MIN,var resolvedAt:LocalDateTime?=null)

