package com.feedback.model.query.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertQueryCriteria(val level:String?=null,val status:String?=null,val active:Boolean=false,val alertId:Long?=null,val clusterId:Long?=null)

