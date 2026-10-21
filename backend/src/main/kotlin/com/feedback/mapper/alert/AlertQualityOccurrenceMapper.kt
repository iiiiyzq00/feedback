package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertQualityOccurrenceMapper{
 fun countOpen(@Param("at")at:LocalDateTime):Double
 fun insertDedup(@Param("row")row:IncorrectDedupInsertPo):Int
 fun repair(@Param("id")id:Long,@Param("note")note:String,@Param("actor")actor:Long):Int
 fun findDedup():List<IncorrectDedupPo>
}

