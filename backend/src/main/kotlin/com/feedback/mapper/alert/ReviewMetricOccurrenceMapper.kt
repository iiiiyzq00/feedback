package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface ReviewMetricOccurrenceMapper{
 fun decisionCount(@Param("at")at:LocalDateTime):Double
 fun overturnRate(@Param("at")at:LocalDateTime):Double
 fun inputCompleteness(@Param("at")at:LocalDateTime):Double?
 fun insertSnapshots(@Param("rows")rows:List<RuleInputSnapshotPo>):Int
}

