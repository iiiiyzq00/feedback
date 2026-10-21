package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class OncallSchedulePo(var id:Long=0,var team:String="",var name:String?=null,var type:String="",var userId:Long=0,
 var escalationUserId:Long?=null,var start:LocalDateTime=LocalDateTime.MIN,var end:LocalDateTime=LocalDateTime.MIN,
 var status:String="",var version:Long=0)

