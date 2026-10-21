package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

data class AlertAcceptancePo(var id:Long=0,var ruleId:Long=0,var replayRunId:Long=0,var drillId:Long=0,
    var truePositive:Long=0,var falsePositive:Long=0,var falseNegative:Long=0,var trueNegative:Long=0,
    var precisionRate:String?=null,var recallRate:String?=null,var routingOutcome:String="",var evidenceSha256:String="",
    var status:String="",var assessedBy:Long?=null,var approvedBy:Long?=null,var assessedAt:LocalDateTime?=null,
    var approvedAt:LocalDateTime?=null)

