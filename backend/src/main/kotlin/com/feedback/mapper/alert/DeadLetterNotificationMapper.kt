package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface DeadLetterNotificationMapper {
 fun count(@Param("channel")channel:String?):Long
 fun list(@Param("channel")channel:String?,@Param("limit")limit:Int,@Param("offset")offset:Int):List<DeadLetterSummaryPo>
 fun find(@Param("id")id:Long):DeadLetterNotificationPo?
 fun replay(@Param("id")id:Long):Int
 fun ignore(@Param("id")id:Long):Int
 fun exists(@Param("id")id:Long):Int
}

