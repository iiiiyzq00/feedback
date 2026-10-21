package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class NotificationOutboxPo(var id:Long=0,var channel:String="",var recipientsJson:String?=null,var content:String="",var retryCount:Int=0,var maxRetries:Int=0,var targetUserId:Long?=null,var alertInstanceId:Long?=null)

