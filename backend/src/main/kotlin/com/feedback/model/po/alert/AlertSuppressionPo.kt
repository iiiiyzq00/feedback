package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertSuppressionPo(var id:Long=0,var type:String="",var scope:String="{}",var start:LocalDateTime=LocalDateTime.MIN,var end:LocalDateTime=LocalDateTime.MIN,var createdAt:LocalDateTime=LocalDateTime.MIN,var suppressedBy:Long?=null)

