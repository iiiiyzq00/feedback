package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertEngineInstanceInsertPo(var id:Long?=null,var ruleId:Long=0,var ruleCode:String="",var severity:String="")

