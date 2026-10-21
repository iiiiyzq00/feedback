package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class DeadLetterSummaryPo(var id:Long=0,var ruleCode:String?=null,var alertInstanceId:Long?=null,var channel:String="",var attempts:Int=0,var deadLetterStatus:String?=null,var httpStatus:Int?=null,var providerCode:String?=null,var errorType:String?=null,var errorSummary:String?=null,var deadLetteredAt:LocalDateTime?=null,var replayCount:Int=0)

