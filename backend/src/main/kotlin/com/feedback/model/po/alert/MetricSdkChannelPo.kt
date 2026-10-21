package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MetricSdkChannelPo(var sourceId:Long=0,var deviceModel:String?=null)

