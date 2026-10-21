package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface AlertQueryInstanceMapper{
 fun page(@Param("query")query:AlertQueryCriteria,@Param("offset")offset:Int,@Param("limit")limit:Int):List<AlertQueryInstancePo>
 fun pageAll(@Param("afterId")afterId:Long,@Param("limit")limit:Int):List<AlertQueryInstancePo>
 fun find(@Param("id")id:Long):AlertQueryInstancePo?
 fun lock(@Param("id")id:Long):AlertQueryInstancePo?
 fun status(@Param("id")id:Long):String?
}

