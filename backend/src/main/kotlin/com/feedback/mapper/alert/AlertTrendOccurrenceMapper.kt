package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertTrendOccurrenceMapper{
 fun countSdk(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Long
 fun upsert(@Param("rows")rows:List<TrendBaselineWritePo>):Int
 fun findBaselines(@Param("metric")metric:String,@Param("dimensions")dimensions:List<String>,@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):List<TrendBaselinePo>
}

