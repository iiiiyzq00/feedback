package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertRuleInsertPo(var id:Long?=null,var ruleCode:String="",var name:String="",var ownerTeam:String?=null,var priority:Int=0,var version:String="",var level:String="",var triggerExpression:String="{}",var recoveryExpression:String="{}",var routingConfig:String="{}",var cooldownMinutes:Int=0,var enabled:Boolean=false)

