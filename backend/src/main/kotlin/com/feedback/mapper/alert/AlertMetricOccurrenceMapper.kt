package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertMetricOccurrenceMapper{
 fun notificationFailureRate(@Param("minutes")minutes:Int):Double
 fun sdkCount(@Param("minutes")minutes:Int):Double
 fun sdkFailureRate(@Param("minutes")minutes:Int):Double
 fun sdkNonRateLimitFailureRate(@Param("minutes")minutes:Int):Double
 fun sdkRateLimitRate(@Param("minutes")minutes:Int):Double
 fun sdkVolume30m():Double
 fun openIdempotencyConflicts():Double
 fun sdkCountBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Double
 fun sdkNonRateLimitFailureBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Double
 fun sdkRateLimitBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Double
 fun sdkVolumeBefore(@Param("end")end:LocalDateTime):Double
 fun openIdempotencyBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Double
}

