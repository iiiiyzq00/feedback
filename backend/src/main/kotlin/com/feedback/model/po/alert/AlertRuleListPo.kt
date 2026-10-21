package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertRuleListPo(var id:Long=0,var ruleCode:String="",var name:String="",var category:String="",var level:String="",var enabled:Boolean=false,var versionStatus:String="",var triggerExpression:String?=null,var recoveryExpression:String?=null,var routingConfig:String?=null,var cooldownMinutes:Int=0,var version:String="",var ownerTeam:String?=null,var priority:Int=0)

