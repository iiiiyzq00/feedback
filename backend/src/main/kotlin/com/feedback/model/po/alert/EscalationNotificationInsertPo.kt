package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class EscalationNotificationInsertPo(var ruleId:Long=0,var ruleCode:String="",var alertId:Long=0,var chainId:Long?=null,var targetId:Long?=null,var userId:Long?=null,var deadlineSeconds:Int=0,var sequence:Int=0,var channel:String="",var content:String="")

