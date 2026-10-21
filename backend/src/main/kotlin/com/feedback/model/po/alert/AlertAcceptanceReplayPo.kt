package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertAcceptanceReplayPo(var id:Long=0,var ruleId:Long=0,var testMode:String="",
    var runStatus:String="",var externalNotificationsSent:Int=0)

