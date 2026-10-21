package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface ReviewMetricSdkMapper{fun countBreaches(@Param("at")at:LocalDateTime,@Param("rules")rules:List<ReviewSlaPo>,@Param("exact")exact:Set<String>,@Param("fallback")fallback:ReviewSlaPo?):Double}

