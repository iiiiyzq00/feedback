package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IdempotencyConflictPo(
    var id:Long=0,var sourceChannel:String="",var importBatchId:Long=0,var importRowId:Long=0,
    var existingSourceId:Long=0,var reason:String="",var status:String="",var resolutionAction:String?=null,
    var resolutionNote:String?=null,var occurredAt:LocalDateTime?=null,var resolvedAt:LocalDateTime?=null,
    var resolvedBy:Long?=null
)

