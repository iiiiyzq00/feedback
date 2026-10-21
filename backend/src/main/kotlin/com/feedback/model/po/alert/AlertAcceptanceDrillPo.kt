package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertAcceptanceDrillPo(var ruleId:Long=0,var productionImpact:Int=0,var notificationChannel:String="")

