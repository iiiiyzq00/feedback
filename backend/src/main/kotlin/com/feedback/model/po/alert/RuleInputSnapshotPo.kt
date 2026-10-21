package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class RuleInputSnapshotPo(val ruleCode:String,val start:LocalDateTime,val end:LocalDateTime,val sampleCount:Long,val presentCount:Long,val completeness:Double)

