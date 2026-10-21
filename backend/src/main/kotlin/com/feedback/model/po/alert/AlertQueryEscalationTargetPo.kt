package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryEscalationTargetPo(var targetOrder:Int=0,var targetType:String="",var displayName:String?=null,var status:String?=null,var channels:String?=null)

