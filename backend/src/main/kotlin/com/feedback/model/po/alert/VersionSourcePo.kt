package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class VersionSourcePo(var id:Long=0,var packageName:String="",var appVersion:String="",var sourceChannel:String="",var ingestTime:LocalDateTime=LocalDateTime.MIN)

