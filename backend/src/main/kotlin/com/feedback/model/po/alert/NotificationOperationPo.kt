package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class NotificationOperationPo(var id:Long=0,var ruleCode:String?=null,var alertInstanceId:Long?=null,
 var channel:String="",var status:String="",var attempts:Int=0,var maxRetries:Int=0,var nextAttemptAt:LocalDateTime?=null,
 var lastAttemptAt:LocalDateTime?=null,var deliveredAt:LocalDateTime?=null,var errorType:String?=null,
 var errorSummary:String?=null,var providerMessageId:String?=null,var createdAt:LocalDateTime?=null)

