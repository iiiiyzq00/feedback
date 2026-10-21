package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MetricSourcePo(var id:Long=0,var sourceChannel:String="",var appVersion:String?=null,var ingestTime:LocalDateTime?=null,var sourceEventTime:LocalDateTime?=null)

