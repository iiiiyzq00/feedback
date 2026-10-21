package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryVisibilitySourcePo(var id:Long=0,var channel:String="",var province:String?=null,var city:String?=null)

