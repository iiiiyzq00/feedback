package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertEscalationCandidatePo(var id:Long=0,var ruleId:Long=0,var ruleCode:String="",var instanceSeverity:String="",var firedAt:LocalDateTime=LocalDateTime.MIN)

