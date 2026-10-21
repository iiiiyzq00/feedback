package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class NotificationEnqueuePo(var ruleId:Long?=null,var ruleCode:String="",var instanceId:Long?=null,var channel:String="",var policyVersion:String="",var mode:String="",var recipientsJson:String="[]",var content:String="")

