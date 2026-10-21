package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertRuleLastTriggeredPo(var ruleId:Long=0,var lastTriggeredAt:LocalDateTime?=null)

