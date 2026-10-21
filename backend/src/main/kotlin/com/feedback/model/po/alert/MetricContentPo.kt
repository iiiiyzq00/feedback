package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MetricContentPo(var sourceId:Long=0,var maskingStatus:String="",var deleted:Boolean=false,var issueCategory:String?=null,var severity:String?=null,var sentiment:String?=null,var description:String?=null)

