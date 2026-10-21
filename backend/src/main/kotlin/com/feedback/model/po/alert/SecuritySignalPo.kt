package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class SecuritySignalPo(
    var id:Long=0,
    var signalType:String="",
    var evidenceLevel:String="",
    var riskLevel:String="",
    var sourceType:String="",
    var sourceReferenceHash:String="",
    var evidenceSnapshot:String="{}",
    var status:String="OPEN",
    var occurredAt:LocalDateTime?=null,
    var conclusion:String?=null,
    var concludedAt:LocalDateTime?=null
)

