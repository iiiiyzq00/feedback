package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class DistributionTaskPo(
    var id:Long=0,var distributionBatchId:Long?=null,var deliveryStatus:String="PENDING",
    var compensationStatus:String="NOT_REQUIRED",var taskStatus:String="DELIVERED",var feedbackSourceId:Long=0,
    var toOwnerUserId:Long=0,var fromOwnerUserId:Long?=null,var previousProcessingStatus:String?=null,
    var providerIdempotencyKey:String=""
)

