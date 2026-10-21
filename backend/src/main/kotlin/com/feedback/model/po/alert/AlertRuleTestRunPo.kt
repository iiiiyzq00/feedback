package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertRuleTestRunPo(var rawId:Long=0,var id:Long=0,var ruleId:Long?=null,var mode:String?=null,var status:String?=null,var matched:Boolean?=null,var sampleSize:Int?=null,var externalNotificationsSent:Int?=null,var createdAt:LocalDateTime?=null,var completedAt:LocalDateTime?=null,var userId:Long=0)

