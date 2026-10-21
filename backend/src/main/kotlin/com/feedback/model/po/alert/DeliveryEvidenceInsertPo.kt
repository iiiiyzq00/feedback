package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class DeliveryEvidenceInsertPo(var id:Long=0,val actor:Long,val target:String,val type:String,val reference:String,val sha256:String?,val note:String?)

