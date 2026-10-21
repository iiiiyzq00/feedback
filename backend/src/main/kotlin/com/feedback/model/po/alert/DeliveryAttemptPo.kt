package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class DeliveryAttemptPo(var id:Long=0,var attempt:Int=0,var channel:String?=null,var status:String?=null,var startedAt:LocalDateTime?=null,var completedAt:LocalDateTime?=null,var durationMs:Long?=null,var httpStatus:Int?=null,var providerCode:String?=null,var providerMessageId:String?=null,var errorType:String?=null,var errorSummary:String?=null)

