package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class EscalationTargetInsertPo(var chainId:Long=0,var order:Int=0,var type:String="",var userId:Long?=null,var name:String="",var channelsJson:String="[]")

