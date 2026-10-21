package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class DeadLetterNotificationPo(var id:Long=0,var ruleId:Long?=null,var ruleCode:String?=null,var alertInstanceId:Long?=null,var channel:String="",var status:String="",var attempts:Int=0,var maxRetries:Int=0,var deadLetterStatus:String?=null,var deadLetteredAt:LocalDateTime?=null,var providerMessageId:String?=null,var httpStatus:Int?=null,var providerCode:String?=null,var errorType:String?=null,var errorSummary:String?=null,var replayCount:Int=0,var createdAt:LocalDateTime=LocalDateTime.MIN,var updatedAt:LocalDateTime=LocalDateTime.MIN)

