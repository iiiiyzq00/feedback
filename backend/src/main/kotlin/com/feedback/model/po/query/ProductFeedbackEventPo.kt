package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ProductFeedbackEventPo(
    var id:Long=0,var eventType:String="",var actorUserId:Long=0,var previousHandlerUserId:Long?=null,
    var newHandlerUserId:Long?=null,var oldStatus:String?=null,var newStatus:String?=null,var reason:String?=null,
    var createdAt:LocalDateTime?=null
)

