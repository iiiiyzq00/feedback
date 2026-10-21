package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ProductFeedbackEventInsertPo(val requestId:Long,val eventType:String,val actorUserId:Long,
    val previousHandlerUserId:Long?,val newHandlerUserId:Long?,val oldStatus:String?,val newStatus:String?,val reason:String)

