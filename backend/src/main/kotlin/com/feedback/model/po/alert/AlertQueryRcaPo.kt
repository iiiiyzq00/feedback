package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryRcaPo(var id:Long=0,var title:String="",var description:String="",var solution:String?=null,var confidence:BigDecimal?=null,var status:String="",var orchestrationVersion:String?=null,var correlationJson:String?=null,var impactJson:String?=null,var validationStatus:String?=null,var validationJson:String?=null,var factsSha256:String?=null,var createdAt:LocalDateTime=LocalDateTime.MIN)

