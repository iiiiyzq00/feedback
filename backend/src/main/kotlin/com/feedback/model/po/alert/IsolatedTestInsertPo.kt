package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IsolatedTestInsertPo(var id:Long?=null,var ruleId:Long=0,var mode:String="",var matched:Boolean=false,var inputJson:String="{}",var resultJson:String="{}",var start:LocalDateTime?=null,var end:LocalDateTime?=null,var sampleSize:Long=0,var actor:Long=0)

