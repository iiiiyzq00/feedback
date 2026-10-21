package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryOccurrencePo(var id:Long=0,var instanceId:Long=0,var snapshot:String?=null,var evaluatedAt:LocalDateTime?=null)

