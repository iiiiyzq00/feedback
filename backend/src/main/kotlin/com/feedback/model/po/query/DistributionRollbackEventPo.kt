package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class DistributionRollbackEventPo(
    val sourceId:Long,val actor:Long,val previousOwner:Long,val newOwner:Long?,val processingStatus:String,
    val taskId:Long,val reason:String?
)

