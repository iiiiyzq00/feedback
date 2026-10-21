package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class EscalationChainPo(var id:Long=0,var displayId:Long=0,var alertId:Long=0,var incidentId:Long?=null,var ruleId:Long=0,var ruleCode:String="",var severity:String="",var ownerTeam:String?=null,var targetOrder:Int=1,var channelOrder:Int=1,var status:String="")

