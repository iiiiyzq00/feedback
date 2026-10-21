package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class TrendLinkPo(var feedbackSourceId:Long=0,var clusterId:Long=0)

