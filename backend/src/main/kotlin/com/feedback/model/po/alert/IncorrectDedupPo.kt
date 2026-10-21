package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IncorrectDedupPo(var id:Long=0,var feedbackSourceId:Long=0,var relatedSourceId:Long?=null,
 var errorType:String="",var urgency:String="",var status:String="",var confirmationNote:String?=null,
 var confirmedAt:LocalDateTime?=null,var repairNote:String?=null,var repairedAt:LocalDateTime?=null,var confirmedBy:Long?=null)

