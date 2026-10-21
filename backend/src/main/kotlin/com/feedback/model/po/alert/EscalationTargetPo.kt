package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class EscalationTargetPo(var id:Long=0,var displayId:Long=0,var type:String="",var userId:Long?=null,var channelsJson:String="[]",var order:Int=0)

