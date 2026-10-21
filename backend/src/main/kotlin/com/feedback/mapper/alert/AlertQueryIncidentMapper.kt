package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface AlertQueryIncidentMapper{
 fun count():Long;fun page(@Param("limit")limit:Int,@Param("offset")offset:Int):List<AlertQueryIncidentPo>;fun find(@Param("id")id:Long):AlertQueryIncidentPo?;fun full(@Param("id")id:Long):AlertQueryIncidentFullPo?
 fun transfer(@Param("id")id:Long,@Param("user")user:Long,@Param("reason")reason:String,@Param("actor")actor:Long,@Param("version")version:Long):Int
}

