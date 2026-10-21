package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class DistributionBatchPo(
    var id:Long=0,var batchStatus:String="",var initiatedBy:Long=0,var failureReason:String?=null,
    var compensationAttempts:Int=0
)

