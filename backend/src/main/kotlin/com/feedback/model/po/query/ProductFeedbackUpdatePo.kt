package com.feedback.model.po.query

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class ProductFeedbackUpdatePo(val id:Long,val status:String,val handler:Long?,val priority:String,
    val plannedVersion:String?,val resolutionNote:String?)

