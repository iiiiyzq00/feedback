package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertEngineInstanceStatePo(var status:String="",var lifecycleVersion:Long=0,var severity:String="",var clusterId:Long?=null)

