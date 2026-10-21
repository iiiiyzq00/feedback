package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class NotificationAttemptPo(var notificationId:Long=0,var alertInstanceId:Long?=null,var attempt:Int=0,var channel:String="",var status:String="",var durationMs:Long=0,var httpStatus:Int?=null,var providerCode:String?=null,var providerMessageId:String?=null,var errorType:String?=null,var errorSummary:String?=null,var durationMicros:Long=0)

