package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ExternalAlertInsertPo(var id:Long=0,val ruleCode:String,val occurredAt:LocalDateTime)

