package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertMetricImportRowMapper{fun count(@Param("minutes")minutes:Int):Double;fun errorRate(@Param("minutes")minutes:Int):Double;fun countBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Double;fun errorBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Double}

