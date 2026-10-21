package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class TrendBaselineWritePo(val metric:String,val dimension:String,val sampleCount:Long,val start:LocalDateTime)

