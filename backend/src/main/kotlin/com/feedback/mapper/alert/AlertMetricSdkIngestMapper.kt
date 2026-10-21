package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertMetricSdkIngestMapper{fun urgentBreaches():Double;fun failedRecoveryBreaches():Double;fun urgentBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Double;fun failedBetween(@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Double}

