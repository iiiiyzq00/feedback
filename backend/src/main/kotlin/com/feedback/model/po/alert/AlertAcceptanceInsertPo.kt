package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertAcceptanceInsertPo(var id:Long=0,val ruleId:Long,val parentTaskId:Long,val drillId:Long,
    val replayRunId:Long,val truePositive:Long,val falsePositive:Long,val falseNegative:Long,val trueNegative:Long,
    val precision:BigDecimal,val recall:BigDecimal,val routingOutcome:String,val evidenceSha256:String,val actor:Long)

