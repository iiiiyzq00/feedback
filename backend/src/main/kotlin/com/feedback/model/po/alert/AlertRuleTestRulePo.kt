package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertRuleTestRulePo(var id:Long=0,var ruleCode:String="",var ruleName:String="")

