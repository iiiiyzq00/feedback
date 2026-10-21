package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MetricClusterPo(var id:Long=0,var status:String="",var statusChangedAt:LocalDateTime?=null,var createdAt:LocalDateTime?=null)

