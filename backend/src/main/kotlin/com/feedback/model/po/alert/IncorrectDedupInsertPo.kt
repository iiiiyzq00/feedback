package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class IncorrectDedupInsertPo(var id:Long=0,val sourceId:Long,val relatedId:Long?,val errorType:String,
 val urgency:String,val note:String,val actor:Long)

