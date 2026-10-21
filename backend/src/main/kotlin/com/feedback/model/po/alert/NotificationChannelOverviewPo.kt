package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class NotificationChannelOverviewPo(var channel:String="",var total:Long=0,var delivered:Long=0,var failed:Long=0,
 var openDeadLetters:Long=0,var deliveryRate:BigDecimal?=null)

