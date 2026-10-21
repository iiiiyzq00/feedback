package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class VersionReleasePo(var id:Long=0,var packageName:String="",var appVersion:String="",var controlVersion:String="",var releasedAt:LocalDateTime=LocalDateTime.MIN,var status:String="",var createdAt:LocalDateTime=LocalDateTime.MIN)

