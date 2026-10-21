package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQuerySilencePo(var id:Long=0,var endsAt:LocalDateTime=LocalDateTime.MIN,var scope:String="{}")

