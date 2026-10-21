package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertRuleLockedPo(var ruleCode:String="",var version:String="",var enabled:Boolean=false,var priority:Int=0)

