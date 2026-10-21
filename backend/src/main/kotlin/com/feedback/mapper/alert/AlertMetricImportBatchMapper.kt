package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertMetricImportBatchMapper{fun maxChannelStale():Double?;fun failed(@Param("minutes")minutes:Int):Double;fun rowCount(@Param("minutes")minutes:Int):Double;fun failureRate(@Param("minutes")minutes:Int):Double;fun qualityRows(@Param("minutes")minutes:Int):Double;fun qualityScore(@Param("minutes")minutes:Int):Double;fun failedBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Double;fun rowsBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Double;fun failureBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Double;fun qualityRowsBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Double;fun qualityScoreBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Double}

