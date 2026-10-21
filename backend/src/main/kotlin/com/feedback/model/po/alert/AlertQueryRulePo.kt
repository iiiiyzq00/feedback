package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryRulePo(var id:Long=0,var ruleCode:String="",var ruleName:String="",var ownerTeam:String?=null,var category:String="",var conditionJson:String="{}",var routeJson:String="{}")

