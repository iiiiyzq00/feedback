package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import java.time.LocalDateTime

data class AlertCategoryCoveragePo(var category:String="",var ruleCount:Long=0,var activeCount:Long=0)

