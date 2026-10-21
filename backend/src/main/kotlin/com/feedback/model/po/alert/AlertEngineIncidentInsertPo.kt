package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertEngineIncidentInsertPo(var id:Long?=null,var title:String="",var severity:String="")

