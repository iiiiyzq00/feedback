package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertLifecycleRulePo(var id:Long=0,var ruleCode:String="",var ruleName:String="",var ownerTeam:String?=null,var routingConfig:String="{}",var versionStatus:String="")

