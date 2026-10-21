package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertSuppressionInsertPo(var id:Long=0,val type:String,val scopeJson:String,val start:LocalDateTime,val end:LocalDateTime,val actor:Long)

