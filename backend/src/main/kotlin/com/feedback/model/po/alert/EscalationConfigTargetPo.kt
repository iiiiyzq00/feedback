package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class EscalationConfigTargetPo(var type:String="",var userId:Long=0,var ordering:Int=0)

