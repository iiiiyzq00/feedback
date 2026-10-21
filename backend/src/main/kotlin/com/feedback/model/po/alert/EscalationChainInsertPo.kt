package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class EscalationChainInsertPo(var ruleId:Long=0,var ruleCode:String="",var alertId:Long=0,var incidentId:Long?=null,var ownerTeam:String?=null,var severity:String="")

