package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertLifecycleInstanceMapper {
 fun cooldownCount(@Param("code")code:String,@Param("minutes")minutes:Int):Int
 fun pageOpen(@Param("afterId")afterId:Long,@Param("limit")limit:Int):List<AlertEscalationCandidatePo>
 fun updateSeverity(@Param("ids")ids:List<Long>,@Param("target")target:String):Int
}

