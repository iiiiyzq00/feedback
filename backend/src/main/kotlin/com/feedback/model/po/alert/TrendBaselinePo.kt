package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class TrendBaselinePo(var dimensionKey:String="",var bucketStart:LocalDateTime=LocalDateTime.MIN,var sampleCount:Long=0)

