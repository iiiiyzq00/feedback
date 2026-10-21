package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface OncallConfigMapper{
 fun teams():List<OncallTeamPo>
 fun members(@Param("team")team:String?):List<OncallMemberPo>
 fun memberByLegacyId(@Param("id")id:Long):OncallMemberPo?
 fun countFutureSchedules(@Param("team")team:String,@Param("userId")userId:Long):Int
 fun calendar(@Param("team")team:String,@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):List<OncallSchedulePo>
 fun countConflicts(@Param("team")team:String,@Param("type")type:String,@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime):Int
 fun isMember(@Param("userId")userId:Long,@Param("team")team:String):Int
}

