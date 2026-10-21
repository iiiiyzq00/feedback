package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class MetricJiraPo(var rawId:Long=0,var sourceId:Long=0,var createdAt:LocalDateTime?=null)

