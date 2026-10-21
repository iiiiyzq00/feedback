package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class AlertRuleTestInsertPo(var id:Long?=null,var ruleId:Long?=null,var matched:Boolean=false,var inputJson:String="{}",var resultJson:String="{}",var sampleSize:Int=0,var actor:Long=0)

