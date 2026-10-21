package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class DeliveryEvidencePo(var id:Long=0,var type:String="",var reference:String="",var sha256:String?=null,var note:String?=null,var createdAt:LocalDateTime=LocalDateTime.MIN)

