package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertEngineRulePo(var id:Long=0,var ruleCode:String="",var ruleName:String="",var defaultSeverity:String="",var triggerExpression:String="{}",var recoveryExpression:String?=null,var routingConfig:String="{}",var cooldownMinutes:Int=0,var ownerTeam:String?=null)

