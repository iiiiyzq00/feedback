package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class NotificationCompletionPo(var id:Long=0,var httpStatus:Int?=null,var providerCode:String?=null,var providerMessageId:String?=null,var errorType:String?=null,var errorSummary:String?=null,var attempt:Int=0,var delaySeconds:Long=0)

